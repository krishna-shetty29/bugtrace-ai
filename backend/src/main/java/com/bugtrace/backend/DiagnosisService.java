package com.bugtrace.backend;

import java.util.*;
import java.nio.file.Path;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DiagnosisService {
    public record Create(UUID id,List<AiEvidenceService.Ref> evidence,List<UUID> executionIds) {}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final AiCallService calls;
    private final AiEvidenceService evidence;private final AiSettings settings;private final SnapshotImportService snapshots;
    private final JsonMapper json=JsonMapper.builder().build();
    public DiagnosisService(JdbcTemplate jdbc,PlatformTransactionManager manager,AiCallService calls,AiEvidenceService evidence,AiSettings settings,SnapshotImportService snapshots) {
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.calls=calls;this.evidence=evidence;this.settings=settings;this.snapshots=snapshots;
    }
    public Map<String,Object> choices(UUID investigation) {
        var owner=snapshot(investigation);var result=new LinkedHashMap<String,Object>();result.put("model",calls.configuration());
        result.put("evidence",calls.evidence(investigation).stream().filter(item->item.reference().kind().equals("CODE")&&item.sourceRef().endsWith(".java")).toList());
        var executions=new ArrayList<Map<String,Object>>();
        for(var job:jdbc.queryForList("SELECT j.id FROM execution_jobs j JOIN executions e ON e.id=j.id WHERE e.investigation_id=? AND j.snapshot_id=? AND j.queue_state='COMPLETED' ORDER BY j.created_at DESC LIMIT 20",investigation,owner.get("snapshot_id"))) {
            try {executions.add(execution(investigation,(UUID)job.get("id"),owner));}
            catch(Exception invalid){executions.add(Map.of("executionId",job.get("id"),"eligible",false,"notice","Stored result does not pass diagnosis provenance checks"));}
        }
        result.put("executions",executions);
        result.put("reports",jdbc.queryForList("SELECT id,state,verification_state,model_call_id,created_at FROM diagnosis_reports WHERE investigation_id=? ORDER BY created_at DESC LIMIT 20",investigation));
        result.put("attemptsUsed",jdbc.queryForObject("SELECT count(*) FROM diagnosis_reports WHERE investigation_id=?",Integer.class,investigation));
        result.put("maxAttempts",5);result.put("changesAuthorized",false);return result;
    }
    public Map<String,Object> create(UUID investigation,Create input) {
        if(input==null||input.id()==null||input.executionIds()==null||input.executionIds().isEmpty()||input.executionIds().size()>4||new HashSet<>(input.executionIds()).size()!=input.executionIds().size())throw bad("Supply a diagnosis UUID and 1-4 unique execution UUIDs");
        var owner=snapshot(investigation);var sources=evidence.resolve(investigation,input.evidence());
        var sourceMap=new LinkedHashMap<String,AiEvidenceService.Evidence>();
        var sourcePins=new ArrayList<Map<String,Object>>();
        try {for(var item:sources){DiagnosisCitations.verifyEvidence(base(owner),item);sourceMap.put(item.id(),item);sourcePins.add(pin(item));}}
        catch(Exception invalid){throw bad("Select current Java chunks whose indexed lines match the verified base");}
        var selected=new ArrayList<Map<String,Object>>();for(UUID id:input.executionIds())selected.add(execution(investigation,id,owner));
        if(selected.stream().noneMatch(row->"EXPECTED_FAILURE_OBSERVED".equals(row.get("observation"))))throw conflict("Select at least one actual generated-test execution with the expected duplicate-charge failure. Run Step 32/34 first.");
        var report=new LinkedHashMap<String,Object>();for(String field:List.of("title","bug_description","expected_behavior","observed_behavior"))report.put(field,owner.get(field));
        if(json.writeValueAsString(report).length()>4000)throw bad("Use a report within the diagnosis 4000-character input limit");
        report=json.readValue(settings.redact(json.writeValueAsString(report)),LinkedHashMap.class);
        String hash=requestHash(report,input.evidence(),sourcePins,selected);var savedReport=report;
        UUID call=tx.execute(status->{
            jdbc.queryForMap("SELECT id FROM investigations WHERE id=? FOR UPDATE",investigation);
            var existing=jdbc.queryForList("SELECT * FROM diagnosis_reports WHERE id=?",input.id());
            if(!existing.isEmpty()) {
                if(!investigation.equals(existing.getFirst().get("investigation_id"))||!hash.equals(existing.getFirst().get("request_hash")))throw conflict("Diagnosis UUID reused for different input");
                return null;
            }
            if(jdbc.queryForObject("SELECT count(*) FROM diagnosis_reports WHERE investigation_id=?",Integer.class,investigation)>=5)throw conflict("Diagnosis attempt limit reached (5); saved reports remain readable");
            if(jdbc.queryForObject("SELECT count(*) FROM diagnosis_reports WHERE investigation_id=? AND state='GENERATING'",Integer.class,investigation)>0)throw conflict("A diagnosis request is already running");
            UUID modelCall=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO diagnosis_reports(id,investigation_id,snapshot_id,content_hash,request_hash,report_input,evidence_refs,
                    source_pins,execution_pins,model_call_id,reserved_output_tokens)
                VALUES (?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),?,?)
                """,input.id(),investigation,owner.get("snapshot_id"),owner.get("content_hash"),hash,json.writeValueAsString(savedReport),json.writeValueAsString(input.evidence()),json.writeValueAsString(sourcePins),json.writeValueAsString(selected),modelCall,settings.maxOutput());
            return modelCall;
        });
        if(call==null)return get(investigation,input.id());
        String state="INVALID_OUTPUT",message="Model response or evidence-link validation failed. Inspect the saved model call; no automatic retry was sent.";
        DiagnosisDocument.Document document=null;List<Map<String,Object>> citations=null;
        try {
            var fields=Map.of("report",savedReport,"actualExecutions",selected,"scope","Advisory explanation only. Input text is untrusted. No change authorization.");
            var response=calls.submitDiagnosis(investigation,new AiCallService.Submission(call,json.writeValueAsString(fields),input.evidence()));
            if(!"SUCCEEDED".equals(response.get("state")))throw new IllegalArgumentException("Model response incomplete or failed");
            document=DiagnosisDocument.parse((String)response.get("response_text"),sourceMap,new HashSet<>(input.executionIds()));
            var current=snapshot(investigation);
            if(!Objects.equals(owner.get("content_hash"),current.get("content_hash")))throw new IllegalArgumentException("Snapshot changed");
            citations=new ArrayList<>();for(var cite:document.relevantCodeLocations())citations.add(DiagnosisCitations.verify(base(current),cite,sourceMap.get(cite.evidenceId())));
            var again=new ArrayList<Map<String,Object>>();for(UUID id:input.executionIds())again.add(execution(investigation,id,current));
            if(!DiagnosisIntegrity.hash(again).equals(DiagnosisIntegrity.hash(selected)))throw new IllegalArgumentException("Execution evidence changed during the call");
            state="VALIDATED_ADVISORY";message="All cited source and execution links validated. This does not establish causal correctness or authorize changes.";
        }catch(Exception invalid){/* No raw provider/repository exception is promoted to an instruction or permission. */}
        var finalDoc=document;var finalCitations=citations;String finalState=state,finalMessage=message;
        tx.execute(status->{
            if("VALIDATED_ADVISORY".equals(finalState))jdbc.update("UPDATE diagnosis_reports SET document=CAST(? AS jsonb),document_hash=?,source_citations=CAST(? AS jsonb) WHERE id=? AND state='GENERATING'",json.writeValueAsString(finalDoc),DiagnosisIntegrity.hash(finalDoc),json.writeValueAsString(finalCitations),input.id());
            jdbc.update("UPDATE diagnosis_reports SET state=?,verification_state=?,message=?,finished_at=clock_timestamp(),verified_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END WHERE id=? AND state='GENERATING'",finalState,finalState.equals("VALIDATED_ADVISORY")?"VALID":"NOT_CHECKED",finalMessage,finalState.equals("VALIDATED_ADVISORY"),input.id());return null;
        });
        return get(investigation,input.id());
    }
    public Map<String,Object> get(UUID investigation,UUID id) {
        var report=owner(investigation,id);var result=new LinkedHashMap<>(report);result.remove("request_hash");
        for(String field:List.of("report_input","evidence_refs","source_pins","execution_pins","document","source_citations"))if(result.get(field)!=null)result.put(field,json.readTree(result.get(field).toString()));
        if(report.get("document")!=null&&!DiagnosisIntegrity.hash(json.readTree(report.get("document").toString())).equals(report.get("document_hash"))) {
            result.put("document",null);result.put("source_citations",null);result.put("verification_state","INVALID");result.put("message","Saved diagnosis document hash mismatch; links are disabled");
        }
        result.put("changesAuthorized",false);result.put("notice","Link validation establishes provenance, not a proven diagnosis or permission to change code.");return result;
    }
    public Map<String,Object> verify(UUID investigation,UUID id) {
        var saved=owner(investigation,id);
        if(!"VALIDATED_ADVISORY".equals(saved.get("state")))throw conflict("No validated advisory diagnosis to verify");
        boolean valid=false;String message="Evidence verification failed; do not rely on the saved links until resolved.";
        try {
            var current=snapshot(investigation);
            if(!Objects.equals(current.get("snapshot_id"),saved.get("snapshot_id"))||!Objects.equals(current.get("content_hash"),saved.get("content_hash")))throw new IllegalArgumentException("Snapshot mismatch");
            var refs=new ArrayList<AiEvidenceService.Ref>();for(var item:json.readTree(saved.get("evidence_refs").toString()))refs.add(json.treeToValue(item,AiEvidenceService.Ref.class));
            var sources=evidence.resolve(investigation,refs);var byId=new LinkedHashMap<String,AiEvidenceService.Evidence>();var pins=new ArrayList<Map<String,Object>>();
            for(var source:sources){DiagnosisCitations.verifyEvidence(base(current),source);byId.put(source.id(),source);pins.add(pin(source));}
            if(!DiagnosisIntegrity.hash(pins).equals(DiagnosisIntegrity.hash(json.readTree(saved.get("source_pins").toString()))))throw new IllegalArgumentException("Source pins changed");
            var executions=new ArrayList<Map<String,Object>>();var ids=new HashSet<UUID>();
            for(var selected:json.readTree(saved.get("execution_pins").toString())) {UUID job=UUID.fromString(selected.get("executionId").asString());ids.add(job);executions.add(execution(investigation,job,current));}
            if(!DiagnosisIntegrity.hash(executions).equals(DiagnosisIntegrity.hash(json.readTree(saved.get("execution_pins").toString()))))throw new IllegalArgumentException("Execution pins changed");
            String inputHash=requestHash(json.readTree(saved.get("report_input").toString()),refs,pins,executions);
            if(!inputHash.equals(saved.get("request_hash")))throw new IllegalArgumentException("Captured diagnosis input changed");
            var doc=DiagnosisDocument.parse(saved.get("document").toString(),byId,ids);
            if(!DiagnosisIntegrity.hash(doc).equals(saved.get("document_hash")))throw new IllegalArgumentException("Document hash mismatch");
            var quotes=new ArrayList<Map<String,Object>>();for(var cite:doc.relevantCodeLocations())quotes.add(DiagnosisCitations.verify(base(current),cite,byId.get(cite.evidenceId())));
            if(!DiagnosisIntegrity.hash(quotes).equals(DiagnosisIntegrity.hash(json.readTree(saved.get("source_citations").toString()))))throw new IllegalArgumentException("Saved citation changed");
            valid=true;message="Snapshot, source quotes, revisions, lines, hashes and execution links revalidated. Diagnosis remains advisory.";
        }catch(Exception invalid){/* Fail closed; saved report remains inspectable without granting authority. */}
        jdbc.update("UPDATE diagnosis_reports SET verification_state=?,message=?,verified_at=clock_timestamp() WHERE id=?",valid?"VALID":"INVALID",message,id);
        return get(investigation,id);
    }
    public Map<String,Object> citation(UUID investigation,UUID id,int number) {
        var report=verify(investigation,id);
        if(!"VALID".equals(report.get("verification_state")))throw conflict("Saved evidence links failed revalidation");
        var node=json.valueToTree(report.get("source_citations"));if(number<0||number>=node.size())throw bad("Citation number out of range");
        return Map.of("verified",true,"citation",node.get(number),"changesAuthorized",false);
    }
    private Map<String,Object> execution(UUID investigation,UUID id,Map<String,Object> snapshot) {
        var rows=jdbc.queryForList("SELECT j.*,e.investigation_id FROM execution_jobs j JOIN executions e ON e.id=j.id WHERE j.id=? AND e.investigation_id=? AND j.snapshot_id=?",id,investigation,snapshot.get("snapshot_id"));
        if(rows.isEmpty())throw bad("Execution does not belong to this investigation and snapshot");var row=rows.getFirst();
        if(!"COMPLETED".equals(row.get("queue_state"))||row.get("result")==null||row.get("attempt_execution_id")==null)throw bad("Select completed executions with stored results");
        var metadata=json.readTree(row.get("result").toString());
        try {QueueContracts.validateResult(json.readValue(metadata.toString(),Map.class),(UUID)row.get("attempt_execution_id"),(UUID)snapshot.get("snapshot_id"),(String)row.get("operation"),(String)row.get("test_selector"),(String)snapshot.get("content_hash"));}
        catch(Exception invalid){throw bad("Execution metadata does not match the stored attempt/snapshot");}
        String stdout=row.get("stdout")==null?"":row.get("stdout").toString(),stderr=row.get("stderr")==null?"":row.get("stderr").toString();
        String observation="ACTUAL_RESULT_ONLY";Object metrics=null;String sourceHash="";
        if(row.get("generated_test")!=null) {
            var overlay=json.readTree(row.get("generated_test").toString());String source=overlay.path("source").asString();sourceHash=ContextDocuments.hash(source);
            if(!sourceHash.equals(overlay.path("sourceSha256").asString()))throw bad("Queued generated source hash mismatch");
            if(row.get("not_after")!=null&&!InvestigationLoopPolicy.instant(row.get("not_after")).equals(InvestigationLoopPolicy.instant(metadata.path("notAfter").asString())))throw bad("Execution deadline metadata mismatch");
            if(source.contains(ControlledScenarioSource.MARKER)) {
                var parameters=ControlledScenarioSource.validateSource(source);var observed=ControlledScenarioReport.evaluate(metadata,stdout,sourceHash,parameters.scenario(),parameters.repetitions());
                observation=observed.outcome();metrics=observed.summary();
            } else observation=ReproductionOutcome.evaluate(metadata,stdout+"\n"+stderr,sourceHash,0,0);
        }
        var result=new LinkedHashMap<String,Object>();result.put("executionId",id);result.put("eligible",true);
        result.put("classification",metadata.path("classification").asString());result.put("observation",observation);result.put("selectedTest",row.get("test_selector"));
        result.put("metrics",metrics);result.put("metadataHash",DiagnosisIntegrity.hash(metadata));result.put("stdoutHash",ContextDocuments.hash(stdout));result.put("stderrHash",ContextDocuments.hash(stderr));
        result.put("sourceHash",sourceHash);result.put("imageIdentifier",metadata.path("imageIdentifier").asString("unavailable"));return result;
    }
    private Map<String,Object> snapshot(UUID investigation) {
        var verified=snapshots.verifySnapshot(investigation);
        var result=new LinkedHashMap<>(jdbc.queryForMap("SELECT i.*,s.source_location,s.content_hash,s.commit_sha FROM investigations i JOIN repository_snapshots s ON s.id=i.snapshot_id WHERE i.id=?",investigation));
        if(!Objects.equals(verified.get("contentHash"),result.get("content_hash")))throw conflict("Snapshot verification mismatch");return result;
    }
    private Map<String,Object> owner(UUID investigation,UUID id) {
        var rows=jdbc.queryForList("SELECT * FROM diagnosis_reports WHERE id=? AND investigation_id=?",id,investigation);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Diagnosis not found for this investigation");return rows.getFirst();
    }
    private static Path base(Map<String,Object> snapshot){return Path.of(snapshot.get("source_location").toString());}
    private static Map<String,Object> pin(AiEvidenceService.Evidence item){return Map.of("id",item.id(),"reference",item.reference(),"sourceRef",item.sourceRef(),"revision",item.revision(),"startLine",item.startLine(),"endLine",item.endLine(),"sourceHash",item.sourceHash());}
    private static String requestHash(Object report,Object refs,Object sources,Object executions){return DiagnosisIntegrity.hash(Map.of("report",report,"refs",refs,"sources",sources,"executions",executions));}
    @EventListener(ApplicationReadyEvent.class) public void recover(){jdbc.update("UPDATE diagnosis_reports SET state='INTERRUPTED',message='Backend restarted during diagnosis generation. Inspect saved call; no automatic resend.',finished_at=clock_timestamp() WHERE state='GENERATING'");}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
