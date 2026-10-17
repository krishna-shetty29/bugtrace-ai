package com.bugtrace.backend;

import java.util.*;
import java.nio.file.Path;
import java.time.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class RoleToolGateway {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AiEvidenceService evidence;
    private final SnapshotImportService snapshots;
    private final DurableQueueService queue;
    private final AiSettings settings;
    private final JsonMapper json=JsonMapper.builder().build();
    public RoleToolGateway(JdbcTemplate jdbc,PlatformTransactionManager manager,AiEvidenceService evidence,
        SnapshotImportService snapshots,DurableQueueService queue,AiSettings settings) {
        this.jdbc=jdbc;transactions=new TransactionTemplate(manager);this.evidence=evidence;this.snapshots=snapshots;this.queue=queue;this.settings=settings;
    }
    public List<Map<String,Object>> list(UUID investigation,UUID run) {
        owned(investigation,run,false);
        var rows=jdbc.queryForList("SELECT * FROM role_tool_calls WHERE run_id=? ORDER BY created_at,id",run);
        for(var row:rows){row.remove("request_hash");for(String field:List.of("arguments","result"))if(row.get(field)!=null)row.put(field,json.readTree(row.get(field).toString()));}
        return rows;
    }
    /** Called under the coordinator's run-row lock, never treats pending jobs as results. */
    void refreshExecutions(UUID investigation,UUID run,Map<String,Object> owner) {
        var actual=new ArrayList<Object>();var ids=new HashSet<String>();
        for(var item:json.readTree(owner.get("execution_results").toString())){actual.add(item);ids.add(item.path("executionId").asString());}
        var jobs=jdbc.queryForList("""
            SELECT j.id,j.operation,j.test_selector,j.queue_state,j.result,j.completed_at
            FROM role_tool_calls t JOIN execution_jobs j ON j.id=t.execution_id JOIN executions e ON e.id=j.id
            WHERE t.run_id=? AND e.investigation_id=? AND j.snapshot_id=? ORDER BY j.created_at
            """,run,investigation,owner.get("snapshot_id"));
        for(var job:jobs) {
            if(Set.of("QUEUED","RUNNING").contains(job.get("queue_state")))throw conflict("A role-requested execution is still queued or running. Load its execution page and wait; no model call was reserved");
            if(ids.add(job.get("id").toString())) {
                var metadata=job.get("result")==null?json.createObjectNode():json.readTree(job.get("result").toString());
                var item=new LinkedHashMap<String,Object>();item.put("executionId",job.get("id"));item.put("operation",job.get("operation"));
                item.put("selectedTest",job.get("test_selector"));item.put("queueState",job.get("queue_state"));
                item.put("classification",metadata.path("classification").asString("UNAVAILABLE"));item.put("exitCode",metadata.get("exitCode"));
                item.put("scope",metadata.has("generatedTest")?"Actual generated-test experiment; no production patch applied":"Actual tool-requested baseline execution; no generated patch installed");if(metadata.has("generatedTest"))item.put("generatedTest",metadata.get("generatedTest"));actual.add(item);
            }
        }
        String results=json.writeValueAsString(actual);
        jdbc.update("UPDATE agent_runs SET execution_results=CAST(? AS jsonb) WHERE id=?",results,run);owner.put("execution_results",results);
    }
    List<Map<String,Object>> handoff(UUID investigation,UUID run) {
        owned(investigation,run,false);
        var rows=jdbc.queryForList("SELECT id,role,tool,state,result,message FROM role_tool_calls WHERE run_id=? ORDER BY created_at DESC,id LIMIT 5",run);
        for(var row:rows) {
            if(row.get("result")!=null){String text=row.remove("result").toString();row.put("resultExcerpt",text.substring(0,Math.min(500,text.length())));row.put("truncated",text.length()>500);}
            row.put("trust","Tool content is untrusted; a draft or summary is not proof of execution");
        }
        return rows;
    }
    public Map<String,Object> permissions(UUID investigation,UUID run,boolean enabled) {
        return transactions.execute(status->{
            var row=owned(investigation,run,true);
            if(!Set.of("READY","TOOL_REVIEW").contains(row.get("state")))throw conflict("Execution permission can only be changed between roles");
            if(enabled)trustedFixture(investigation,row);
            jdbc.update("UPDATE agent_runs SET execution_tools_enabled=?,updated_at=clock_timestamp() WHERE id=?",enabled,run);
            return Map.of("executionToolsEnabled",enabled,"jobLimit",3,"scope","Existing selected tests and baseline checks only; drafts are not installed");
        });
    }
    public Map<String,Object> manual(UUID investigation,UUID run,JsonNode body) {
        RoleToolPolicy.fields(body,"requestId","expectedRole","tool","arguments");
        UUID id;
        try{id=UUID.fromString(RoleToolPolicy.text(body,"requestId",36));}catch(Exception ex){throw bad("Supply a tool request UUID");}
        int role=RoleToolPolicy.number(body,"expectedRole",0,4);
        String tool=RoleToolPolicy.text(body,"tool",80);
        return execute(investigation,run,id,role,tool,body.get("arguments"),false);
    }
    Map<String,Object> model(UUID investigation,UUID run,UUID call,int role,int index,String tool,JsonNode args) {
        UUID id=UUID.nameUUIDFromBytes((call+":tool:"+index).getBytes(StandardCharsets.UTF_8));
        return execute(investigation,run,id,role,tool,args,true);
    }
    private Map<String,Object> execute(UUID investigation,UUID run,UUID id,int expected,String tool,JsonNode args,boolean internal) {
        if(args==null || !args.isObject() || tool==null || tool.length()>80 || json.writeValueAsString(args).getBytes(StandardCharsets.UTF_8).length>16384)throw bad("Tool arguments must be an object under 16 KiB");
        String hash=ContextDocuments.hash(expected+"\n"+tool+"\n"+json.writeValueAsString(args));
        return transactions.execute(status->{
            var row=owned(investigation,run,true);
            var old=jdbc.queryForList("SELECT run_id,request_hash FROM role_tool_calls WHERE id=?",id);
            if(!old.isEmpty()) {
                if(!run.equals(old.getFirst().get("run_id")) || !hash.equals(old.getFirst().get("request_hash")))throw conflict("Tool UUID was used for different input");
                return saved(id);
            }
            if(((Number)row.get("used_tool_calls")).intValue()>=30)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Run tool-request limit reached; no action was executed");
            int current=((Number)row.get("next_role")).intValue();
            String role=current>=0 && current<5?AgentRolePolicy.role(current).name():"NONE";
            jdbc.update("UPDATE agent_runs SET used_tool_calls=used_tool_calls+1 WHERE id=?",run);
            jdbc.update("INSERT INTO role_tool_calls(id,run_id,role_number,role,tool,request_hash,arguments,state) VALUES (?,?,?,?,?,?,CAST(? AS jsonb),'STARTED')",id,run,current,role,tool,hash,settings.redact(json.writeValueAsString(args)));
            try {
                if(current!=expected || current>=5 || !(Set.of("READY","TOOL_REVIEW").contains(row.get("state")) || (internal && "RUNNING".equals(row.get("state")))))throw new IllegalArgumentException("Tool request is stale or run is not at an active role");
                if(!deadline(row).isAfter(Instant.now()))throw new IllegalArgumentException("Run deadline has expired");
                RoleToolPolicy.authorize(AgentRolePolicy.role(current),tool);
                var snapshot=jdbc.queryForMap("SELECT s.id,s.source_location,s.content_hash,s.module_path FROM investigations i JOIN repository_snapshots s ON s.id=i.snapshot_id WHERE i.id=?",investigation);
                if(!Objects.equals(snapshot.get("id"),row.get("snapshot_id")))throw new IllegalArgumentException("Run snapshot changed");
                snapshots.verifySnapshot(investigation);
                var refs=new ArrayList<AiEvidenceService.Ref>();
                for(var ref:json.readTree(row.get("evidence_refs").toString()))refs.add(json.treeToValue(ref,AiEvidenceService.Ref.class));
                var selected=evidence.resolve(investigation,refs);
                Object result=dispatch(investigation,run,id,AgentRolePolicy.role(current),tool,args,row,snapshot,selected);
                snapshots.verifySnapshot(investigation);
                String output=settings.redact(json.writeValueAsString(result));
                if(output.getBytes(StandardCharsets.UTF_8).length>32768)throw new IllegalArgumentException("Tool result exceeds 32 KiB; narrow the request");
                jdbc.update("UPDATE role_tool_calls SET state='SUCCEEDED',result=CAST(? AS jsonb),finished_at=clock_timestamp() WHERE id=?",output,id);
            }catch(IllegalArgumentException ex){
                jdbc.update("UPDATE role_tool_calls SET state='DENIED',message=?,finished_at=clock_timestamp() WHERE id=?",settings.redact(ex.getMessage()),id);
            }catch(Exception ex){
                jdbc.update("UPDATE role_tool_calls SET state='FAILED',message='Tool could not complete; no automatic retry',finished_at=clock_timestamp() WHERE id=?",id);
            }
            return saved(id);
        });
    }
    private Object dispatch(UUID investigation,UUID run,UUID request,AgentRolePolicy.Role role,String tool,JsonNode args,
        Map<String,Object> row,Map<String,Object> snapshot,List<AiEvidenceService.Evidence> selected) throws Exception {
        var paths=new HashSet<String>();for(var item:selected)if(currentSource(item))paths.add(item.sourceRef());
        switch(tool) {
            case "searchCode": {
                RoleToolPolicy.fields(args,"query");String query=RoleToolPolicy.text(args,"query",200).toLowerCase(Locale.ROOT);
                return Map.of("scope","Selected captured code chunks only","untrusted",true,"matches",selected.stream().filter(item->item.origin().equals("CURRENT_SNAPSHOT_CODE") && (item.text().toLowerCase(Locale.ROOT).contains(query) || item.sourceRef().toLowerCase(Locale.ROOT).contains(query))).map(item->Map.of("evidenceId",item.id(),"path",item.sourceRef(),"startLine",item.startLine(),"endLine",item.endLine(),"snippet",item.text().substring(0,Math.min(item.text().length(),1200)))).toList());
            }
            case "readEvidence": {
                RoleToolPolicy.fields(args,"evidenceId");String id=RoleToolPolicy.text(args,"evidenceId",2500);
                return Map.of("untrusted",true,"evidence",selected.stream().filter(item->item.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Evidence is outside this run's selected scope")));
            }
            case "readFile", "readRelevantFiles": {
                RoleToolPolicy.fields(args,"path","startLine","endLine");String path=RoleToolPolicy.text(args,"path",1000);
                int start=RoleToolPolicy.number(args,"startLine",1,1000000),end=RoleToolPolicy.number(args,"endLine",1,1000000);
                var source=selected.stream().filter(item->currentSource(item) && item.sourceRef().equals(path) && start>=item.startLine() && end<=item.endLine()).findFirst().orElseThrow(()->new IllegalArgumentException("Read range must be within one selected current-source snippet"));
                String text=RoleToolFiles.read(Path.of(snapshot.get("source_location").toString()),path,start,end);
                return Map.of("untrusted",true,"evidenceId",source.id(),"path",path,"startLine",start,"endLine",end,"sourceHash",ContextDocuments.hash(text),"text",text);
            }
            case "createTest": {
                RoleToolPolicy.fields(args,"path","source");String path=RoleToolPolicy.text(args,"path",1000),source=RoleToolPolicy.text(args,"source",10000);
                if(!RoleToolPolicy.testPath(path))throw new IllegalArgumentException("Test draft path must be under src/test/java and end in .java");
                return Map.of("artifactId",request,"kind","TEST_DRAFT","path",path,"source",source,"applied",false,"compiled",false,"executed",false);
            }
            case "readDiagnosis": {
                RoleToolPolicy.fields(args);
                var rows=jdbc.queryForList("SELECT call_id,output FROM agent_role_steps WHERE run_id=? AND role='DIAGNOSIS_AGENT' AND state='VALIDATED'",run);
                if(rows.isEmpty())throw new IllegalArgumentException("No validated diagnosis proposal is saved for this run");
                return Map.of("untrusted",true,"scope","Model proposal, not verified cause","callId",rows.getFirst().get("call_id"),"diagnosis",json.readTree(rows.getFirst().get("output").toString()));
            }
            case "proposePatch": {
                RoleToolPolicy.fields(args,"diff");String diff=RoleToolPolicy.text(args,"diff",10000);RoleToolPolicy.patchHeaders(diff,paths);
                return Map.of("artifactId",request,"kind","PATCH_DRAFT","diff",diff,"applied",false,"verified",false,"notice","Only selected-file header paths checked; context/applicability and code safety remain unvalidated");
            }
            case "readExecutionResults", "readTestReports": {
                RoleToolPolicy.fields(args,"executionId");UUID execution=uuid(RoleToolPolicy.text(args,"executionId",36));
                var job=execution(investigation,run,execution,row);
                var result=new LinkedHashMap<String,Object>();result.put("executionId",execution);result.put("queueState",job.get("queue_state"));result.put("untrusted",true);
                result.put("result",job.get("result")==null?null:json.readTree(job.get("result").toString()));
                if(tool.equals("readTestReports")) {
                    var metadata=job.get("result")==null?json.createObjectNode():json.readTree(job.get("result").toString());
                    result.put("result",Map.of("classification",metadata.path("classification").asString("PENDING"),"junit",metadata.path("junit")));
                    result.put("notice","Saved JUnit metadata only; raw XML is not downloaded by this tool");
                }else{result.put("stdoutExcerpt",job.get("stdout"));result.put("stderrExcerpt",job.get("stderr"));}
                return result;
            }
            case "requestExperiment", "requestApprovedChecks": {
                RoleToolPolicy.fields(args,"operation","testSelector","timeoutSeconds");
                if(!Boolean.TRUE.equals(row.get("execution_tools_enabled")))throw new IllegalArgumentException("Execution tools are disabled; enable them for this trusted run in the browser first");
                trustedFixture(investigation,row);
                String operation=RoleToolPolicy.text(args,"operation",40);
                if(tool.equals("requestExperiment") && !operation.equals("RUN_SELECTED_TEST"))throw new IllegalArgumentException("Reproduction may only request an existing selected test");
                QueueContracts.Operation approved;
                try{approved=QueueContracts.Operation.valueOf(operation);}catch(Exception ex){throw new IllegalArgumentException("Unapproved worker operation");}
                String selector=args.get("testSelector").isNull()?null:RoleToolPolicy.text(args,"testSelector",200);
                if(approved==QueueContracts.Operation.RUN_SELECTED_TEST && !RoleToolPolicy.selectedTest(selector,paths))throw new IllegalArgumentException("Test selector must identify a selected captured test file");
                int timeout=RoleToolPolicy.number(args,"timeoutSeconds",1,300);
                if(deadline(row).isBefore(Instant.now().plusSeconds(timeout+5L)))throw new IllegalArgumentException("Requested execution timeout does not fit remaining run time");
                if(jdbc.queryForObject("SELECT count(*) FROM role_tool_calls WHERE run_id=? AND execution_id IS NOT NULL",Integer.class,run)>=3)throw new IllegalArgumentException("Run execution-job limit is three");
                if(jdbc.queryForObject("SELECT count(*) FROM role_tool_calls t JOIN execution_jobs j ON j.id=t.execution_id WHERE t.run_id=? AND j.queue_state IN ('QUEUED','RUNNING')",Integer.class,run)>0)throw new IllegalArgumentException("Wait for the run's existing execution before requesting another");
                var job=new QueueContracts.CreateJob(approved,selector,timeout);QueueContracts.validate(job);
                var created=queue.enqueue(investigation,job);
                UUID id=(UUID)created.get("executionId");jdbc.update("UPDATE role_tool_calls SET execution_id=? WHERE id=?",id,request);
                return Map.of("executionId",id,"queueState",created.get("queueState"),"scope","Existing baseline snapshot only; generated drafts are not installed");
            }
            case "createVerificationSummary": {
                RoleToolPolicy.fields(args,"executionIds");var ids=args.get("executionIds");
                if(!ids.isArray() || ids.isEmpty() || ids.size()>5)throw new IllegalArgumentException("Select one to five scoped execution IDs");
                var results=new ArrayList<Object>();var unique=new HashSet<UUID>();
                for(var value:ids) {
                    if(!value.isString())throw new IllegalArgumentException("Execution IDs must be strings");UUID id=uuid(value.asString());
                    if(!unique.add(id))throw new IllegalArgumentException("Duplicate execution ID");
                    var job=execution(investigation,run,id,row);
                    if(!"COMPLETED".equals(job.get("queue_state")))throw new IllegalArgumentException("Summary requires completed saved executions");
                    results.add(Map.of("executionId",id,"classification",json.readTree(job.get("result").toString()).path("classification").asString("UNAVAILABLE")));
                }
                return Map.of("artifactId",request,"verdict","RECORDED_RESULTS_ONLY_NO_PATCH_VERIFICATION","executions",results,"patchApplied",false);
            }
            default:throw new IllegalArgumentException("Unknown tool");
        }
    }
    private Map<String,Object> execution(UUID investigation,UUID run,UUID id,Map<String,Object> owner) {
        boolean selected=false;for(var item:json.readTree(owner.get("execution_results").toString()))if(id.toString().equals(item.path("executionId").asString()))selected=true;
        if(!selected && jdbc.queryForObject("SELECT count(*) FROM role_tool_calls WHERE run_id=? AND execution_id=?",Integer.class,run,id)==0)throw new IllegalArgumentException("Execution is outside this run");
        var rows=jdbc.queryForList("SELECT j.queue_state,j.result,left(j.stdout,4000) AS stdout,left(j.stderr,4000) AS stderr FROM execution_jobs j JOIN executions e ON e.id=j.id WHERE j.id=? AND e.investigation_id=? AND j.snapshot_id=?",id,investigation,owner.get("snapshot_id"));
        if(rows.isEmpty())throw new IllegalArgumentException("Execution belongs to a different investigation or snapshot");return rows.getFirst();
    }
    private void trustedFixture(UUID investigation,Map<String,Object> run) {
        var rows=jdbc.queryForList("SELECT s.module_path FROM investigations i JOIN repository_snapshots s ON s.id=i.snapshot_id WHERE i.id=? AND s.id=?",investigation,run.get("snapshot_id"));
        if(rows.isEmpty() || !"fixtures/checkout-timeout".equals(rows.getFirst().get("module_path")))throw new IllegalArgumentException("Execution tools currently support the trusted checkout fixture module only");
    }
    private Map<String,Object> saved(UUID id) {
        var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM role_tool_calls WHERE id=?",id));row.remove("request_hash");
        for(String field:List.of("arguments","result"))if(row.get(field)!=null)row.put(field,json.readTree(row.get(field).toString()));return row;
    }
    private Map<String,Object> owned(UUID investigation,UUID run,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM agent_runs WHERE id=? AND investigation_id=?"+(lock?" FOR UPDATE":""),run,investigation);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Run not found for this investigation");return rows.getFirst();
    }
    private static boolean currentSource(AiEvidenceService.Evidence item){return item.lineKind().equals("SOURCE_LINE") && Set.of("CURRENT_SNAPSHOT_CODE","SNAPSHOT_DOCUMENT","SNAPSHOT_CONFIG").contains(item.origin());}
    private static UUID uuid(String value){try{return UUID.fromString(value);}catch(Exception ex){throw new IllegalArgumentException("Invalid execution UUID");}}
    private static Instant deadline(Map<String,Object> row){Object value=row.get("deadline");return value instanceof OffsetDateTime date?date.toInstant():((java.sql.Timestamp)value).toInstant();}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
