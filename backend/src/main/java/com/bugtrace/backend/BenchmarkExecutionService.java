package com.bugtrace.backend;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Explicit operator actions only. A retry UUID replays saved work, never dispatches twice. */
@Service
public class BenchmarkExecutionService {
    public record Execute(UUID requestId,boolean trustedFixtureApproved) {}
    public record Repair(UUID requestId) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BenchmarkRecordScope scope;
    private final DurableQueueService queue;
    private final AiCallService calls;
    private final AiSettings settings;
    private final JsonMapper json=JsonMapper.builder().build();
    public BenchmarkExecutionService(JdbcTemplate jdbc,PlatformTransactionManager manager,BenchmarkRecordScope scope,DurableQueueService queue,AiCallService calls,AiSettings settings) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.scope=scope;this.queue=queue;this.calls=calls;this.settings=settings;
    }
    public List<Map<String,Object>> list(UUID investigation,UUID run) {
        var c=scope.required(investigation);scope.run(c,run,false);
        var result=new ArrayList<Map<String,Object>>();
        for(var row:jdbc.queryForList("SELECT execution_id FROM benchmark_execution_links WHERE run_id=? ORDER BY created_at,execution_id",run))result.add(view(c,run,(UUID)row.get("execution_id"),false));
        return result;
    }
    public Map<String,Object> get(UUID investigation,UUID run,UUID execution) {return view(scope.required(investigation),run,execution,true);}
    private Map<String,Object> view(BenchmarkRecordScope.Context c,UUID run,UUID execution,boolean logs) {
        scope.run(c,run,false);
        var rows=jdbc.queryForList("SELECT l.*,j.benchmark_binding,v.source_document,v.source_sha256 FROM benchmark_execution_links l JOIN execution_jobs j ON j.id=l.execution_id JOIN benchmark_source_versions v ON v.run_id=l.run_id AND v.version=l.source_version WHERE l.run_id=? AND l.execution_id=?",run,execution);
        if(rows.size()!=1)throw reject("Execution does not belong to this case run");
        var row=rows.getFirst();var binding=json.readTree(row.get("benchmark_binding").toString());
        if(!DiagnosisIntegrity.hash(binding).equals(row.get("binding_sha256"))||!c.registrationId().toString().equals(binding.path("registrationId").asText())
            ||!c.investigationId().toString().equals(binding.path("investigationId").asText())||!c.snapshotId().toString().equals(binding.path("snapshotId").asText())
            ||!c.contentHash().equals(binding.path("contentHash").asText())||!run.toString().equals(binding.path("runId").asText())
            ||!c.registration().caseId().equals(binding.path("caseId").asText())||!row.get("source_sha256").equals(binding.path("sourceSha256").asText()))throw reject("Execution binding integrity differs");
        var result=new LinkedHashMap<>(queue.view(execution));
        if(!c.investigationId().equals(result.get("investigationId"))||!c.snapshotId().equals(result.get("snapshotId")))throw reject("Queue execution belongs to another snapshot");
        var spec=json.treeToValue(json.readTree(row.get("source_document").toString()),BenchmarkReproduction.Spec.class);
        if(!ContextDocuments.hash(spec.testSource()).equals(row.get("source_sha256")))throw reject("Executed source version hash differs");
        if(result.get("result") instanceof Map<?,?> metadata)BenchmarkExecutionPolicy.validateResult((Map<String,Object>)metadata,binding);
        result.put("sourceVersion",row.get("source_version"));result.put("sourceSha256",row.get("source_sha256"));result.put("binding",binding);
        result.put("observation",BenchmarkExecutionPolicy.observation(json.valueToTree(result.get("result")),spec.expectedFailureSignature()));
        if(logs)result.put("logs",queue.logs(execution));
        return result;
    }
    public Map<String,Object> execute(UUID investigation,UUID run,Execute input) {
        if(input==null||input.requestId()==null||!input.trustedFixtureApproved())throw reject("Explicit trusted-fixture execution approval and request UUID are required");
        var c=scope.required(investigation);
        UUID execution=tx.execute(status->{
            var row=scope.run(c,run,true);
            var old=jdbc.queryForList("SELECT run_id,execution_id FROM benchmark_execution_links WHERE request_id=?",input.requestId());
            if(!old.isEmpty()){if(!run.equals(old.getFirst().get("run_id")))throw reject("Execution request UUID belongs to another run");return (UUID)old.getFirst().get("execution_id");}
            if(!"TEST_READY".equals(row.get("state")))throw reject("Execute only a validated TEST_READY proposal");
            requireNoActive(run);
            int timeout=BenchmarkExecutionPolicy.timeout(number(row,"experiment_jobs"),row.get("deadline"),Instant.now());
            var spec=spec(c,row);int version=saveSource(c,run,row,spec);
            if(jdbc.queryForObject("SELECT count(*) FROM benchmark_execution_links WHERE run_id=? AND source_version=?",Integer.class,run,version)>0)throw reject("This source version already has an experiment; inspect its saved result");
            var binding=new LinkedHashMap<String,Object>();binding.put("registrationId",c.registrationId());binding.put("investigationId",investigation);binding.put("snapshotId",c.snapshotId());
            binding.put("contentHash",c.contentHash());binding.put("caseId",c.registration().caseId());binding.put("collectionVersion",c.registration().collectionVersion());binding.put("releaseCommit",c.registration().releaseCommit());
            binding.put("runId",run);binding.put("sourceVersion",version);binding.put("sourceSha256",ContextDocuments.hash(spec.testSource()));binding.put("environment",c.registration().environment());binding.put("notAfter",BenchmarkReproductionService.deadlineInstant(row.get("deadline")).toString());binding.put("timeoutSeconds",timeout);
            var draft=queue.prepareBenchmark(c.registrationId(),investigation,c.snapshotId(),c.contentHash(),c.registration(),spec);
            UUID id=(UUID)queue.enqueueBenchmark(investigation,c.snapshotId(),c.registration().generatedTestClass(),timeout,BenchmarkReproductionService.deadlineInstant(row.get("deadline")),draft.get("generatedTest"),binding).get("executionId");
            jdbc.update("INSERT INTO benchmark_execution_links(request_id,run_id,execution_id,source_version,binding_sha256) VALUES (?,?,?,?,?)",input.requestId(),run,id,version,DiagnosisIntegrity.hash(binding));
            jdbc.update("UPDATE benchmark_reproduction_runs SET experiment_jobs=experiment_jobs+1,message='Experiment queued; inspect actual results',updated_at=clock_timestamp() WHERE id=?",run);
            return id;
        });
        return get(investigation,run,execution);
    }
    private void requireNoActive(UUID run) {
        if(jdbc.queryForObject("SELECT count(*) FROM benchmark_execution_links l JOIN execution_jobs j ON j.id=l.execution_id WHERE l.run_id=? AND j.queue_state IN ('QUEUED','RUNNING')",Integer.class,run)>0)throw reject("An experiment is still queued or running");
    }
    private BenchmarkReproduction.Spec spec(BenchmarkRecordScope.Context c,Map<String,Object> row) {
        if(row.get("source_document")==null||row.get("execution_draft")==null)throw reject("No generated source proposal");
        var allowed=new HashSet<String>();for(var id:json.readTree(row.get("evidence_ids").toString()))allowed.add(id.asText());
        var spec=BenchmarkReproduction.parse(row.get("source_document").toString(),allowed,c.registration());
        var draft=json.readTree(row.get("execution_draft").toString());
        if(!ContextDocuments.hash(spec.testSource()).equals(draft.path("generatedTest").path("sourceSha256").asText())||!spec.testSource().equals(draft.path("generatedTest").path("source").asText()))throw reject("Saved draft source differs");
        return spec;
    }
    static int currentVersion(JdbcTemplate jdbc,UUID run) {
        return jdbc.queryForObject("SELECT count(*) FROM benchmark_reproduction_steps WHERE run_id=? AND action IN ('GENERATE_TEST','REPAIR_TEST') AND state='SUCCEEDED'",Integer.class,run);
    }
    private int saveSource(BenchmarkRecordScope.Context c,UUID run,Map<String,Object> row,BenchmarkReproduction.Spec spec) {
        int version=currentVersion(jdbc,run);if(version<1||version>3)throw reject("No valid generating model step for this source");
        var step=jdbc.queryForMap("SELECT call_id FROM benchmark_reproduction_steps WHERE run_id=? AND action IN ('GENERATE_TEST','REPAIR_TEST') AND state='SUCCEEDED' ORDER BY created_at DESC,id DESC LIMIT 1",run);
        Integer owned=jdbc.queryForObject("SELECT count(*) FROM ai_model_calls WHERE id=? AND investigation_id=? AND snapshot_id=? AND state='SUCCEEDED'",Integer.class,step.get("call_id"),c.investigationId(),c.snapshotId());
        if(owned!=1)throw reject("Generating call does not belong to this run snapshot");
        String hash=ContextDocuments.hash(spec.testSource());
        jdbc.update("INSERT INTO benchmark_source_versions(run_id,version,model_call_id,source_sha256,source_document) VALUES (?,?,?,?,CAST(? AS jsonb)) ON CONFLICT DO NOTHING",run,version,step.get("call_id"),hash,json.writeValueAsString(spec));
        var saved=jdbc.queryForMap("SELECT * FROM benchmark_source_versions WHERE run_id=? AND version=?",run,version);
        if(!hash.equals(saved.get("source_sha256"))||!step.get("call_id").equals(saved.get("model_call_id"))||!DiagnosisIntegrity.hash(spec).equals(DiagnosisIntegrity.hash(json.readTree(saved.get("source_document").toString()))))throw reject("Immutable source version differs");
        return version;
    }
    public Map<String,Object> repair(UUID investigation,UUID run,Repair input) {
        if(input==null||input.requestId()==null)throw reject("Supply a repair request UUID");
        var c=scope.required(investigation);
        var reserved=tx.execute(status->{
            var row=scope.run(c,run,true);
            var prior=jdbc.queryForList("SELECT run_id FROM benchmark_reproduction_steps WHERE id=?",input.requestId());
            if(!prior.isEmpty()){if(!run.equals(prior.getFirst().get("run_id")))throw reject("Repair UUID belongs to another run");return null;}
            if(!"TEST_READY".equals(row.get("state")))throw reject("Only a completed generated-file compile failure may be repaired");
            requireNoActive(run);
            if(number(row,"experiment_jobs")>=3)throw reject("REPAIR_EXHAUSTED: all three experiment attempts are reserved");
            int version=saveSource(c,run,row,spec(c,row));
            var links=jdbc.queryForList("SELECT execution_id FROM benchmark_execution_links WHERE run_id=? AND source_version=?",run,version);
            if(links.isEmpty())throw reject("Execute this source before requesting a compiler repair");
            var execution=view(c,run,(UUID)links.getFirst().get("execution_id"),true);
            var logs=(Map<String,Object>)execution.get("logs");
            BenchmarkExecutionPolicy.requireCompilerRepair(json.valueToTree(execution.get("result")),Objects.toString(logs.get("stdout"),""),Objects.toString(logs.get("stderr"),""),number(row,"compiler_repairs"));
            if(!settings.provider().equals(row.get("provider"))||!settings.model().equals(row.get("model"))||settings.maxOutput()!=number(row,"output_per_call"))throw reject("Model settings changed; create a fresh run");
            if(number(row,"calls")>=number(row,"max_calls")||number(row,"reserved_output_tokens")+settings.maxOutput()>number(row,"max_output_tokens")||!Instant.now().plusSeconds(settings.timeout()).isBefore(BenchmarkReproductionService.deadlineInstant(row.get("deadline"))))throw reject("Model request, token or deadline budget exhausted; no repair request sent");
            jdbc.update("INSERT INTO benchmark_reproduction_steps(id,run_id,action,call_id) VALUES (?,?,'REPAIR_TEST',?)",input.requestId(),run,input.requestId());
            jdbc.update("UPDATE benchmark_reproduction_runs SET compiler_repairs=compiler_repairs+1,calls=calls+1,reserved_output_tokens=reserved_output_tokens+?,state='BUSY',updated_at=clock_timestamp() WHERE id=?",settings.maxOutput(),run);
            var copy=new LinkedHashMap<>(row);String feedback=Objects.toString(logs.get("stdout"),"")+"\n"+Objects.toString(logs.get("stderr"),"");
            // Send bounded compiler diagnostics, never arbitrary complete logs.
            copy.put("compilerFeedback",feedback.lines().filter(line->line.contains("[ERROR]")&&line.contains("GeneratedBenchmarkReproductionTest.java")).limit(12).reduce("",(a,b)->a+b.substring(0,Math.min(300,b.length()))+"\n"));return copy;
        });
        if(reserved==null)return Map.of("replayed",true,"runId",run);
        try {
            var refs=new ArrayList<AiEvidenceService.Ref>();for(var item:json.readTree(reserved.get("evidence_refs").toString()))refs.add(json.treeToValue(item,AiEvidenceService.Ref.class));
            var before=spec(c,reserved);
            var fields=Map.of("trustedCase",Map.of("generatedTestPackage",c.registration().generatedTestPackage(),"generatedTestClass",c.registration().generatedTestClass()),"originalProposal",before,"compilerFeedback",reserved.get("compilerFeedback"));
            var response=calls.submitBenchmarkRepair(investigation,new AiCallService.Submission(input.requestId(),json.writeValueAsString(fields),refs));
            if(!"SUCCEEDED".equals(response.get("state")))throw reject("Repair model call did not succeed; inspect the saved call");
            var ids=new HashSet<String>();for(var id:json.readTree(reserved.get("evidence_ids").toString()))ids.add(id.asText());
            var after=BenchmarkReproduction.parse(response.get("response_text").toString(),ids,c.registration());BenchmarkExecutionPolicy.preserveAssertion(before,after);
            var draft=queue.prepareBenchmark(c.registrationId(),investigation,c.snapshotId(),c.contentHash(),c.registration(),after);
            tx.execute(status->{var row=scope.run(c,run,true);if(!"BUSY".equals(row.get("state")))throw reject("Repair was interrupted; no proposal installed");
                jdbc.update("UPDATE benchmark_reproduction_steps SET state='SUCCEEDED' WHERE id=?",input.requestId());
                jdbc.update("UPDATE benchmark_reproduction_runs SET state='TEST_READY',source_document=CAST(? AS jsonb),execution_draft=CAST(? AS jsonb),message='Repaired proposal saved; inspect and explicitly execute it',updated_at=clock_timestamp() WHERE id=?",json.writeValueAsString(after),json.writeValueAsString(draft),run);
                saveSource(c,run,scope.run(c,run,false),after);return null;});
            return Map.of("runId",run,"state","TEST_READY");
        }catch(Exception failure){String message=Objects.toString(settings.redact(failure.getMessage()),"Repair failed");message=message.substring(0,Math.min(1500,message.length()));
            jdbc.update("UPDATE benchmark_reproduction_steps SET state='FAILED',error=? WHERE id=?",message,input.requestId());
            jdbc.update("UPDATE benchmark_reproduction_runs SET state='INVALID_OUTPUT',message=?,updated_at=clock_timestamp() WHERE id=? AND state='BUSY'",message,run);
            throw reject("Repair failed; reservation remains consumed. Inspect the saved model step.");}
    }
    private static int number(Map<String,Object> row,String key){return ((Number)row.get(key)).intValue();}
    private static ResponseStatusException reject(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
