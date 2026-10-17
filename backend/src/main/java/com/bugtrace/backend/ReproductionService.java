package com.bugtrace.backend;

import java.util.*;
import java.time.*;
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
public class ReproductionService {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final AiCallService calls;private final AiSettings settings;
    private final DurableQueueService queue;private final JsonMapper json=JsonMapper.builder().build();
    public record Generate(UUID requestId,int maxRepairs,int expectedAttempt) {}
    public record Execute(int expectedAttempt,int timeoutSeconds) {}
    public ReproductionService(JdbcTemplate jdbc,PlatformTransactionManager manager,AiCallService calls,AiSettings settings,DurableQueueService queue) {
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.calls=calls;this.settings=settings;this.queue=queue;
    }
    public Map<String,Object> get(UUID investigation,UUID parent) {
        return tx.execute(status->{parent(investigation,parent,true);var run=run(parent);refresh(investigation,parent,run);return view(run);});
    }
    public Map<String,Object> generate(UUID investigation,UUID parent,Generate input,boolean repair) {
        if(input==null || input.requestId()==null || input.maxRepairs()<0 || input.maxRepairs()>2)throw bad("Supply a request UUID and at most two repairs");
        Map<String,Object> reserved=tx.execute(status->{
            var owner=parent(investigation,parent,true);
            var existing=jdbc.queryForList("SELECT * FROM reproduction_runs WHERE agent_run_id=?",parent);
            if(!repair && !existing.isEmpty())return null; // Same parent never silently makes another initial call.
            active(owner);
            if(settings.provider().equals("openai") && (settings.model().equals("mock-v1") || settings.credential().isBlank()))throw bad("Configure a real model/key or use mock mode");
            Map<String,Object> previous=null;UUID id=input.requestId();int attempt=0,max=input.maxRepairs();
            if(repair) {
                if(existing.isEmpty())throw conflict("Generate the initial test first");
                var run=existing.getFirst();refresh(investigation,parent,run);
                attempt=((Number)run.get("attempts_count")).intValue();max=((Number)run.get("max_repairs")).intValue();id=(UUID)run.get("id");
                if(!"COMPILE_ERROR".equals(run.get("state")) || attempt>max || input.expectedAttempt()!=attempt-1)throw conflict("Only the latest generated-file compilation error can be repaired within the saved limit");
                previous=jdbc.queryForMap("SELECT a.*,j.stdout,j.stderr FROM reproduction_attempts a JOIN execution_jobs j ON j.id=a.execution_id WHERE a.reproduction_id=? AND a.attempt_number=?",id,attempt-1);
            }
            if(!AgentRolePolicy.canReserve(((Number)owner.get("used_calls")).intValue(),((Number)owner.get("max_calls")).intValue(),((Number)owner.get("reserved_output_tokens")).intValue(),((Number)owner.get("max_output_tokens")).intValue(),settings.maxOutput(),Instant.now(),deadline(owner),settings.timeout()))throw conflict("Parent model-call, output-token or deadline budget exhausted; no model call sent");
            if(!repair)jdbc.update("INSERT INTO reproduction_runs(id,agent_run_id,state,max_repairs) VALUES (?,?,'GENERATING',?)",id,parent,max);
            UUID attemptId=UUID.randomUUID(),call=UUID.randomUUID();
            jdbc.update("INSERT INTO reproduction_attempts(id,reproduction_id,attempt_number,call_id,state) VALUES (?,?,?,?,'GENERATING')",attemptId,id,attempt,call);
            jdbc.update("UPDATE reproduction_runs SET state='GENERATING',attempts_count=attempts_count+1,message=NULL,updated_at=clock_timestamp() WHERE id=?",id);
            jdbc.update("UPDATE agent_runs SET state='RUNNING',used_calls=used_calls+1,reserved_output_tokens=reserved_output_tokens+?,updated_at=clock_timestamp() WHERE id=?",settings.maxOutput(),parent);
            var result=new LinkedHashMap<>(owner);result.put("attempt_id",attemptId);result.put("call_id",call);result.put("reproduction_id",id);result.put("previous",previous);return result;
        });
        if(reserved==null)return get(investigation,parent);
        UUID attempt=(UUID)reserved.get("attempt_id");String state="DRAFT_READY",message=null;
        try {
            var refs=new ArrayList<AiEvidenceService.Ref>();for(var ref:json.readTree(reserved.get("evidence_refs").toString()))refs.add(json.treeToValue(ref,AiEvidenceService.Ref.class));
            var fields=new LinkedHashMap<String,Object>();fields.put("report",json.readTree(reserved.get("report").toString()));fields.put("fault",GeneratedReproduction.FAULT);fields.put("assertion",GeneratedReproduction.ASSERTION);fields.put("expectedFailureSignature",GeneratedReproduction.SIGNATURE);
            @SuppressWarnings("unchecked") var previous=(Map<String,Object>)reserved.get("previous");
            if(previous!=null) {
                fields.put("previousTest",json.readTree(previous.get("document").toString()).get("testSource"));
                String logs=String.valueOf(previous.get("stdout"))+"\n"+String.valueOf(previous.get("stderr"));
                int position=logs.indexOf(GeneratedReproduction.CLASS+".java");int start=Math.max(0,position-500);
                fields.put("compilerFeedback",logs.substring(start,Math.min(logs.length(),start+3500)));fields.put("feedbackTrust","Untrusted compiler output; repair only the generated Java, never alter the contract or production code");
            }
            var call=calls.submitGenerated(investigation,new AiCallService.Submission((UUID)reserved.get("call_id"),json.writeValueAsString(fields),refs));
            if(!"SUCCEEDED".equals(call.get("state")))throw new IllegalArgumentException("Model response was failed or incomplete; inspect the saved call");
            var ids=new HashSet<String>();for(var item:json.readTree(json.writeValueAsString(call.get("input_evidence"))))ids.add(item.get("id").asString());
            var spec=GeneratedReproduction.parse((String)call.get("response_text"),ids);
            if(previous!=null && ContextDocuments.hash(spec.testSource()).equals(previous.get("source_sha")))throw new IllegalArgumentException("Repair returned unchanged source; test remains invalid");
            jdbc.update("UPDATE reproduction_attempts SET document=CAST(? AS jsonb),source_sha=? WHERE id=?",json.writeValueAsString(spec),ContextDocuments.hash(spec.testSource()),attempt);
        }catch(Exception ex){state="INVALID_OUTPUT";message="Generated response failed model, schema, Java structure or repair validation. Inspect the saved model call; no test was queued.";}
        String finalState=state,finalMessage=message;
        tx.execute(status->{
            jdbc.update("UPDATE reproduction_attempts SET state=?,message=? WHERE id=?",finalState,finalMessage,attempt);
            jdbc.update("UPDATE reproduction_runs SET state=?,message=?,updated_at=clock_timestamp() WHERE id=?",finalState,finalMessage,reserved.get("reproduction_id"));
            jdbc.update("UPDATE agent_runs SET state=CASE WHEN deadline<clock_timestamp() THEN 'BUDGET_EXHAUSTED' ELSE 'READY' END,updated_at=clock_timestamp() WHERE id=? AND state='RUNNING'",parent);return null;
        });
        return get(investigation,parent);
    }
    public Map<String,Object> execute(UUID investigation,UUID parent,Execute input) {
        if(input==null || input.timeoutSeconds()<1 || input.timeoutSeconds()>300)throw bad("Execution timeout must be 1–300 seconds");
        tx.execute(status->{
            var owner=parent(investigation,parent,true);active(owner);var run=run(parent);refresh(investigation,parent,run);
            int number=((Number)run.get("attempts_count")).intValue()-1;
            if(number!=input.expectedAttempt())throw conflict("Stale test version; load the current generated source");
            var attempt=jdbc.queryForMap("SELECT * FROM reproduction_attempts WHERE reproduction_id=? AND attempt_number=?",run.get("id"),number);
            if(attempt.get("execution_id")!=null)return null; // Stable queue replay, no second job.
            if(!"DRAFT_READY".equals(run.get("state")))throw conflict("Only a validated draft can be queued");
            if(!Boolean.TRUE.equals(owner.get("execution_tools_enabled")))throw conflict("Enable execution tools for this trusted fixture run first");
            if(deadline(owner).isBefore(Instant.now().plusSeconds(input.timeoutSeconds()+5L)))throw conflict("Requested timeout does not fit the run deadline");
            if(((Number)owner.get("used_tool_calls")).intValue()>=30 || jdbc.queryForObject("SELECT count(*) FROM role_tool_calls WHERE run_id=? AND execution_id IS NOT NULL",Integer.class,parent)>=3)throw conflict("Run tool/job budget exhausted");
            if(jdbc.queryForObject("SELECT count(*) FROM role_tool_calls t JOIN execution_jobs j ON j.id=t.execution_id WHERE t.run_id=? AND j.queue_state IN ('QUEUED','RUNNING')",Integer.class,parent)>0)throw conflict("Wait for the outstanding execution first");
            var spec=json.readValue(attempt.get("document").toString(),GeneratedReproduction.Spec.class);
            var queued=queue.enqueueGenerated(investigation,spec,input.timeoutSeconds());UUID job=(UUID)queued.get("executionId");
            String args=json.writeValueAsString(Map.of("reproductionId",run.get("id"),"attempt",number,"sourceHash",attempt.get("source_sha")));
            jdbc.update("INSERT INTO role_tool_calls(id,run_id,role_number,role,tool,request_hash,arguments,state,result,execution_id,finished_at) VALUES (?,?,1,'REPRODUCTION_AGENT','requestExperiment',?,CAST(? AS jsonb),'SUCCEEDED',CAST(? AS jsonb),?,clock_timestamp())",attempt.get("id"),parent,ContextDocuments.hash(args),args,json.writeValueAsString(Map.of("executionId",job,"scope","Generated test overlay in a fresh experiment; production base remains unchanged")),job);
            jdbc.update("UPDATE agent_runs SET used_tool_calls=used_tool_calls+1 WHERE id=?",parent);
            jdbc.update("UPDATE reproduction_attempts SET execution_id=?,state='QUEUED' WHERE id=?",job,attempt.get("id"));
            jdbc.update("UPDATE reproduction_runs SET state='QUEUED',updated_at=clock_timestamp() WHERE id=?",run.get("id"));return null;
        });
        return get(investigation,parent);
    }
    public Map<String,Object> abandon(UUID investigation,UUID parent) {
        tx.execute(status->{parent(investigation,parent,true);var run=run(parent);refresh(investigation,parent,run);
            if(!Set.of("DRAFT_READY","COMPILE_ERROR").contains(run.get("state")))throw conflict("Only an idle draft or compiler-error result can be abandoned");
            jdbc.update("UPDATE reproduction_runs SET state='TEST_INVALID',message='Stopped by user; generated test not established',updated_at=clock_timestamp() WHERE id=?",run.get("id"));return null;});
        return get(investigation,parent);
    }
    private void refresh(UUID investigation,UUID parent,Map<String,Object> run) {
        if(!"QUEUED".equals(run.get("state")))return;
        var attempts=jdbc.queryForList("SELECT a.*,j.queue_state,j.result,j.stdout,j.stderr FROM reproduction_attempts a JOIN execution_jobs j ON j.id=a.execution_id JOIN executions e ON e.id=j.id JOIN agent_runs r ON r.id=? WHERE a.reproduction_id=? AND e.investigation_id=? AND j.snapshot_id=r.snapshot_id ORDER BY a.attempt_number DESC LIMIT 1",parent,run.get("id"),investigation);
        if(attempts.isEmpty())return;var attempt=attempts.getFirst();
        if(Set.of("QUEUED","RUNNING").contains(attempt.get("queue_state")))return;
        String result=attempt.get("result")==null?"INFRASTRUCTURE_ERROR":ReproductionOutcome.evaluate(json.readTree(attempt.get("result").toString()),String.valueOf(attempt.get("stdout"))+"\n"+String.valueOf(attempt.get("stderr")),attempt.get("source_sha").toString(),((Number)attempt.get("attempt_number")).intValue(),((Number)run.get("max_repairs")).intValue());
        jdbc.update("UPDATE reproduction_attempts SET state=? WHERE id=?",result,attempt.get("id"));
        jdbc.update("UPDATE reproduction_runs SET state=?,message=?,updated_at=clock_timestamp() WHERE id=?",result,result.equals("EXPECTED_FAILURE_OBSERVED")?"Expected generated-test failure observed; review source and reports before accepting causality":result.equals("REPAIR_EXHAUSTED")?"Generated test remains invalid after the allowed compiler repairs":null,run.get("id"));run.put("state",result);
    }
    private Map<String,Object> view(Map<String,Object> run) {
        var result=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM reproduction_runs WHERE id=?",run.get("id")));
        var attempts=jdbc.queryForList("SELECT a.*,j.queue_state,j.result FROM reproduction_attempts a LEFT JOIN execution_jobs j ON j.id=a.execution_id WHERE reproduction_id=? ORDER BY attempt_number",run.get("id"));
        for(var attempt:attempts)for(String field:List.of("document","result"))if(attempt.get(field)!=null)attempt.put(field,json.readTree(attempt.get(field).toString()));
        result.put("attempts",attempts);result.put("path",GeneratedReproduction.PATH);result.put("selector",GeneratedReproduction.SELECTOR);return result;
    }
    private Map<String,Object> parent(UUID investigation,UUID parent,boolean lock) {
        var rows=jdbc.queryForList("SELECT a.*,s.module_path FROM agent_runs a JOIN investigations i ON i.id=a.investigation_id AND i.snapshot_id=a.snapshot_id JOIN repository_snapshots s ON s.id=a.snapshot_id WHERE a.id=? AND i.id=?"+(lock?" FOR UPDATE OF a":""),parent,investigation);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Parent run not found for this investigation/snapshot");return rows.getFirst();
    }
    private static void active(Map<String,Object> parent) {
        if(!"READY".equals(parent.get("state")) || ((Number)parent.get("next_role")).intValue()!=1)throw conflict("Use this workbench while the parent is READY at REPRODUCTION_AGENT");
        if(!"fixtures/checkout-timeout".equals(parent.get("module_path")))throw bad("Generated reproduction currently supports only the trusted checkout fixture");
    }
    private Map<String,Object> run(UUID parent) {
        var rows=jdbc.queryForList("SELECT * FROM reproduction_runs WHERE agent_run_id=?",parent);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No generated reproduction saved for this run");return rows.getFirst();
    }
    @EventListener(ApplicationReadyEvent.class) public void recover() {
        jdbc.update("UPDATE reproduction_attempts SET state='INTERRUPTED',message='Backend restarted during generation; no automatic retry' WHERE state='GENERATING'");
        jdbc.update("UPDATE reproduction_runs SET state='INTERRUPTED',message='Backend restarted during generation; inspect saved call before starting a new run' WHERE state='GENERATING'");
    }
    private static Instant deadline(Map<String,Object> owner){Object value=owner.get("deadline");return value instanceof OffsetDateTime date?date.toInstant():((java.sql.Timestamp)value).toInstant();}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
