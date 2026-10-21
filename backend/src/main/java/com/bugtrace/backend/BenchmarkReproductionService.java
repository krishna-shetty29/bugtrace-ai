package com.bugtrace.backend;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import tools.jackson.databind.json.JsonMapper;

/** Human-stepped benchmark planning/drafting. This increment never queues a job. */
@Service
public class BenchmarkReproductionService {
    public record Create(UUID id,String query,List<AiEvidenceService.Ref> evidence,int maxCalls,int maxOutputTokens,int wallSeconds){}
    public record Step(UUID requestId){}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final BenchmarkSnapshotService snapshots;
    private final BenchmarkRegistrationService registrations;private final AiCallService calls;private final AiEvidenceService evidence;
    private final AiSettings settings;private final SnapshotSearchService search;private final DurableQueueService queue;
    private final JsonMapper json=JsonMapper.builder().build();
    public BenchmarkReproductionService(JdbcTemplate jdbc,PlatformTransactionManager manager,BenchmarkSnapshotService snapshots,BenchmarkRegistrationService registrations,AiCallService calls,AiEvidenceService evidence,AiSettings settings,SnapshotSearchService search,DurableQueueService queue){this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.snapshots=snapshots;this.registrations=registrations;this.calls=calls;this.evidence=evidence;this.settings=settings;this.search=search;this.queue=queue;}
    private Map<String,Object> binding(UUID investigation){var rows=jdbc.queryForList("SELECT * FROM benchmark_snapshot_imports WHERE investigation_id=?",investigation);if(rows.isEmpty())throw bad("Import a registered benchmark snapshot first");return rows.getFirst();}
    private BenchmarkCatalog.Registration context(UUID investigation){var b=binding(investigation);UUID registration=(UUID)b.get("registration_id");snapshots.verify(registration,investigation);return json.convertValue(registrations.verify(registration).get("registration"),BenchmarkCatalog.Registration.class);}
    public Map<String,Object> settings(UUID investigation){context(investigation);return Map.of("model",calls.configuration(),"promptVersion",BenchmarkReproduction.VERSION,"evidence",calls.evidence(investigation),"runs",jdbc.queryForList("SELECT id,state,created_at FROM benchmark_reproduction_runs WHERE investigation_id=? ORDER BY created_at DESC LIMIT 20",investigation),"executionSupported",true);}
    public Map<String,Object> create(UUID investigation,Create input){
        if(input==null||input.id()==null||input.query()==null||input.query().isBlank()||input.query().length()>500||input.maxCalls()<1||input.maxCalls()>8||input.wallSeconds()<120||input.wallSeconds()>1800||input.maxOutputTokens()<settings.maxOutput()||input.maxOutputTokens()>settings.maxOutput()*8)throw bad("Supply UUID, query and limits: 1-8 calls, 120-1800 seconds, up to eight output reservations");
        var c=context(investigation);var selected=evidence.resolve(investigation,input.evidence());validateEvidence(c,selected);verifySourceText(investigation,c,selected);
        var b=binding(investigation);String hash=DiagnosisIntegrity.hash(input);
        var retrieval=search.search(investigation,input.query(),SnapshotSearchEngine.Kind.ALL,4);
        var report=jdbc.queryForMap("SELECT title,bug_description,expected_behavior,observed_behavior FROM investigations WHERE id=?",investigation);
        if(json.writeValueAsString(report).length()>5000)throw bad("Report exceeds 5000 characters");
        tx.execute(status->{
            jdbc.queryForMap("SELECT id FROM investigations WHERE id=? FOR UPDATE",investigation);
            var existing=jdbc.queryForList("SELECT investigation_id,request_hash FROM benchmark_reproduction_runs WHERE id=?",input.id());
            if(!existing.isEmpty()){if(!investigation.equals(existing.getFirst().get("investigation_id"))||!hash.equals(existing.getFirst().get("request_hash")))throw conflict("Run UUID used for different input");return null;}
            if(jdbc.queryForObject("SELECT count(*) FROM benchmark_reproduction_runs WHERE investigation_id=? AND state IN ('READY_ANALYSIS','PLAN_READY','BUSY','TEST_READY')",Integer.class,investigation)>0)throw conflict("Stop or finish the existing benchmark run first");
            jdbc.update("""
                INSERT INTO benchmark_reproduction_runs(id,investigation_id,registration_id,snapshot_id,content_hash,request_hash,provider,model,max_calls,output_per_call,max_output_tokens,deadline,evidence_refs,evidence_ids,query_text,retrieval,report)
                VALUES (?,?,?,?,?,?,?,?,?,?,?, ?,CAST(? AS jsonb),CAST(? AS jsonb),?,CAST(? AS jsonb),CAST(? AS jsonb))
                """,input.id(),investigation,b.get("registration_id"),b.get("snapshot_id"),b.get("platform_content_hash"),hash,settings.provider(),settings.model(),input.maxCalls(),settings.maxOutput(),input.maxOutputTokens(),OffsetDateTime.now().plusSeconds(input.wallSeconds()),json.writeValueAsString(input.evidence()),json.writeValueAsString(selected.stream().map(AiEvidenceService.Evidence::id).toList()),input.query(),json.writeValueAsString(retrieval),json.writeValueAsString(report));return null;
        });return get(investigation,input.id());
    }
    public static void validateEvidence(BenchmarkCatalog.Registration c,List<AiEvidenceService.Evidence> selected){if(selected.isEmpty())throw new IllegalArgumentException("Select source evidence");for(var e:selected)if(!e.origin().equals("CURRENT_SNAPSHOT_CODE")||!e.revision().equals(c.releaseCommit())||!c.files().containsKey(e.sourceRef()))throw new IllegalArgumentException("Only current code/config evidence from this registered case is permitted");}
    private void verifySourceText(UUID investigation,BenchmarkCatalog.Registration c,List<AiEvidenceService.Evidence> selected) {
        try {
            var row=jdbc.queryForMap("SELECT s.source_location FROM investigations i JOIN repository_snapshots s ON s.id=i.snapshot_id WHERE i.id=?",investigation);
            var base=java.nio.file.Path.of(row.get("source_location").toString());BenchmarkSnapshotFiles.verify(base,c);
            for(var e:selected){String[] lines=java.nio.file.Files.readString(base.resolve(e.sourceRef())).split("\\R",-1);if(e.startLine()<1||e.endLine()<e.startLine()||e.endLine()>lines.length)throw new IllegalArgumentException("Evidence line range outside captured source");String text=String.join("\n",Arrays.copyOfRange(lines,e.startLine()-1,e.endLine()))+"\n";if(!text.equals(e.text())||!ContextDocuments.hash(text).equals(e.sourceHash()))throw new IllegalArgumentException("Evidence text differs from captured source lines");}
        }catch(Exception ex){throw bad("Benchmark evidence validation failed: "+ex.getMessage());}
    }
    public Map<String,Object> get(UUID investigation,UUID run){var row=owner(investigation,run,false);var result=new LinkedHashMap<>(row);for(String key:List.of("evidence_refs","evidence_ids","retrieval","report","plan","source_document","execution_draft"))if(result.get(key)!=null)result.put(key,json.readTree(result.get(key).toString()));result.put("steps",jdbc.queryForList("SELECT id,action,call_id,state,error,created_at FROM benchmark_reproduction_steps WHERE run_id=? ORDER BY created_at,id",run));result.put("executionSupported",true);result.put("mock",row.get("provider").equals("mock"));return result;}
    public Map<String,Object> advance(UUID investigation,UUID run,Step input){
        if(input==null||input.requestId()==null)throw bad("Supply a fresh step request UUID");
        var c=context(investigation);
        var reserved=tx.execute(status->{
            var row=owner(investigation,run,true);
            var old=jdbc.queryForList("SELECT run_id FROM benchmark_reproduction_steps WHERE id=?",input.requestId());
            if(!old.isEmpty()){if(!run.equals(old.getFirst().get("run_id")))throw conflict("Step UUID belongs to another run");return null;}
            String state=row.get("state").toString();if(!Set.of("READY_ANALYSIS","PLAN_READY").contains(state))throw conflict("Run has no model step in this state; reload saved state");
            if(!row.get("provider").equals(settings.provider())||!row.get("model").equals(settings.model())||number(row,"output_per_call")!=settings.maxOutput())throw conflict("Model settings changed; stop and create a fresh run");
            if(number(row,"calls")>=number(row,"max_calls")||number(row,"reserved_output_tokens")+settings.maxOutput()>number(row,"max_output_tokens")||Instant.now().plusSeconds(settings.timeout()).isAfter(deadlineInstant(row.get("deadline")))){state(run,"BUDGET_EXHAUSTED","No model request sent; reservation or deadline exhausted");return null;}
            String action=state.equals("READY_ANALYSIS")?"ANALYZE":"GENERATE_TEST";
            jdbc.update("INSERT INTO benchmark_reproduction_steps(id,run_id,action,call_id) VALUES (?,?,?,?)",input.requestId(),run,action,input.requestId());
            jdbc.update("UPDATE benchmark_reproduction_runs SET calls=calls+1,reserved_output_tokens=reserved_output_tokens+?,state='BUSY',updated_at=clock_timestamp() WHERE id=?",settings.maxOutput(),run);
            var copy=new LinkedHashMap<>(row);copy.put("action",action);return copy;
        });
        if(reserved==null)return get(investigation,run);
        try {
            var refs=new ArrayList<AiEvidenceService.Ref>();for(var node:json.readTree(reserved.get("evidence_refs").toString()))refs.add(json.treeToValue(node,AiEvidenceService.Ref.class));
            var selected=evidence.resolve(investigation,refs);validateEvidence(c,selected);verifySourceText(investigation,c,selected);Set<String> allowed=new HashSet<>(selected.stream().map(AiEvidenceService.Evidence::id).toList());
            var fields=new LinkedHashMap<String,Object>();fields.put("report",json.readTree(reserved.get("report").toString()));fields.put("query",reserved.get("query_text"));fields.put("trustedCase",Map.of("caseId",c.caseId(),"modulePath",c.modulePath(),"generatedTestPackage",c.generatedTestPackage(),"generatedTestClass",c.generatedTestClass(),"releaseCommit",c.releaseCommit()));
            boolean generated=reserved.get("action").equals("GENERATE_TEST");if(generated)fields.put("plan",json.readTree(reserved.get("plan").toString()));
            var response=calls.submitBenchmark(investigation,new AiCallService.Submission(input.requestId(),json.writeValueAsString(fields),refs),generated);
            if(!"SUCCEEDED".equals(response.get("state")))throw new IllegalArgumentException("Model call did not complete; inspect saved call");
            if(generated){var spec=GeneratedReproduction.parseBenchmark((String)response.get("response_text"),allowed,c);var draft=queue.prepareBenchmark((UUID)reserved.get("registration_id"),investigation,(UUID)reserved.get("snapshot_id"),reserved.get("content_hash").toString(),c,spec);jdbc.update("UPDATE benchmark_reproduction_runs SET source_document=CAST(? AS jsonb),execution_draft=CAST(? AS jsonb) WHERE id=?",json.writeValueAsString(spec),json.writeValueAsString(draft),run);state(run,"TEST_READY","Validated proposal saved; execution requires explicit trusted-fixture approval");}
            else {var plan=BenchmarkReproduction.plan((String)response.get("response_text"),allowed);jdbc.update("UPDATE benchmark_reproduction_runs SET plan=CAST(? AS jsonb) WHERE id=?",json.writeValueAsString(plan),run);state(run,plan.outcome().equals("INVESTIGATE")?"PLAN_READY":"FINISHED_NO_PATCH","Advisory plan only; no experiments or changes executed");}
            jdbc.update("UPDATE benchmark_reproduction_steps SET state='SUCCEEDED' WHERE id=?",input.requestId());
        }catch(Exception ex){String message=settings.redact(ex.getMessage());if(message==null)message="Model validation failed";message=message.substring(0,Math.min(1500,message.length()));jdbc.update("UPDATE benchmark_reproduction_steps SET state='FAILED',error=? WHERE id=?",message,input.requestId());state(run,"INVALID_OUTPUT",message);}
        return get(investigation,run);
    }
    public Map<String,Object> stop(UUID investigation,UUID run){return tx.execute(status->{var row=owner(investigation,run,true);if(jdbc.queryForObject("SELECT to_regclass('benchmark_execution_links') IS NOT NULL",Boolean.class)&&jdbc.queryForObject("SELECT count(*) FROM benchmark_execution_links l JOIN execution_jobs j ON j.id=l.execution_id WHERE l.run_id=? AND j.queue_state IN ('QUEUED','RUNNING')",Integer.class,run)>0)throw conflict("Wait for the experiment to finish before stopping");if(row.get("state").equals("BUSY"))throw conflict("A model request is running; reload before stopping");if(Set.of("READY_ANALYSIS","PLAN_READY","TEST_READY").contains(row.get("state").toString()))state(run,"STOPPED","Stopped without execution");return get(investigation,run);});}
    private Map<String,Object> owner(UUID investigation,UUID run,boolean lock){var rows=jdbc.queryForList("SELECT * FROM benchmark_reproduction_runs WHERE id=? AND investigation_id=?"+(lock?" FOR UPDATE":""),run,investigation);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Benchmark run does not belong to investigation");return rows.getFirst();}
    private void state(UUID run,String state,String message){jdbc.update("UPDATE benchmark_reproduction_runs SET state=?,message=?,updated_at=clock_timestamp() WHERE id=?",state,message,run);}
    static Instant deadlineInstant(Object value) {
        if (value instanceof OffsetDateTime date) return date.toInstant();
        if (value instanceof java.sql.Timestamp date) return date.toInstant();
        if (value instanceof Instant instant) return instant;
        throw new IllegalArgumentException("Unsupported persisted benchmark deadline type");
    }
    private static int number(Map<String,Object> row,String key){return ((Number)row.get(key)).intValue();}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    @EventListener(ApplicationReadyEvent.class) public void recover(){jdbc.update("UPDATE benchmark_reproduction_steps SET state='FAILED',error='Backend restarted; no automatic resend' WHERE state='STARTED'");jdbc.update("UPDATE benchmark_reproduction_runs SET state='INTERRUPTED',message='Backend restarted during model step; no automatic resend',updated_at=clock_timestamp() WHERE state='BUSY'");}
}
