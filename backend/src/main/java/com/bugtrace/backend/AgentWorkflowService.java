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
public class AgentWorkflowService {
    public record Create(UUID runId,List<AiEvidenceService.Ref> evidence,List<UUID> executionIds,
        int maxCalls,int maxOutputTokens,int deadlineSeconds) {}
    private final JdbcTemplate jdbc;
    private final AiCallService calls;
    private final AiEvidenceService evidence;
    private final AiSettings settings;
    private final TransactionTemplate transactions;
    private final RoleToolGateway tools;
    private final JsonMapper json=JsonMapper.builder().build();
    public AgentWorkflowService(JdbcTemplate jdbc,AiCallService calls,AiEvidenceService evidence,
        AiSettings settings,PlatformTransactionManager manager,RoleToolGateway tools) {
        this.jdbc=jdbc;this.calls=calls;this.evidence=evidence;this.settings=settings;
        this.transactions=new TransactionTemplate(manager);this.tools=tools;
    }
    public List<Map<String,Object>> options(UUID investigation) {
        return jdbc.queryForList("""
            SELECT j.id AS execution_id,j.operation,j.test_selector,j.result->>'classification' AS classification,
                   j.completed_at FROM execution_jobs j JOIN executions e ON e.id=j.id
            JOIN investigations i ON i.id=e.investigation_id AND i.snapshot_id=j.snapshot_id
            WHERE i.id=? AND j.queue_state='COMPLETED' ORDER BY j.completed_at DESC LIMIT 10
            """,investigation);
    }
    public List<Map<String,Object>> list(UUID investigation) {
        return jdbc.queryForList("SELECT id,state,next_role,used_calls,max_calls,created_at FROM agent_runs WHERE investigation_id=? ORDER BY created_at DESC LIMIT 20",investigation);
    }
    public Map<String,Object> get(UUID investigation,UUID run) {
        var result=new LinkedHashMap<>(row(investigation,run,false));
        result.remove("request_hash");
        for(String key:List.of("report","evidence_refs","execution_results"))result.put(key,json.readTree(result.get(key).toString()));
        var steps=jdbc.queryForList("SELECT * FROM agent_role_steps WHERE run_id=? ORDER BY role_number",run);
        for(var step:steps)if(step.get("output")!=null)step.put("output",json.readTree(step.get("output").toString()));
        result.put("steps",steps);result.put("roles",AgentRolePolicy.Role.values());
        result.put("notice","Generated artifacts stay drafts. Approved tool requests may run existing baseline checks; no generated patch is applied or verified.");
        return result;
    }
    public Map<String,Object> create(UUID investigation,Create input) {
        if(settings.provider().equals("openai") && (settings.model().equals("mock-v1") || settings.credential()==null || settings.credential().isBlank()))
            throw bad("Configure a live model and backend credential, or restart with provider mock and model mock-v1");
        if(input==null || input.runId()==null || input.maxCalls()<1 || input.maxCalls()>5
            || input.maxOutputTokens()<settings.maxOutput() || input.maxOutputTokens()>20000
            || input.deadlineSeconds()<60 || input.deadlineSeconds()>3600
            || input.executionIds()==null || input.executionIds().size()>5
            || new HashSet<>(input.executionIds()).size()!=input.executionIds().size())throw bad("Use a UUID, 1–5 calls, output-token budget at least the configured per-call limit (max 20000), 60–3600 seconds, and at most five unique execution IDs");
        String hash=ContextDocuments.hash(json.writeValueAsString(input));
        var existing=jdbc.queryForList("SELECT id FROM agent_runs WHERE id=?",input.runId());
        if(!existing.isEmpty()) {
            var saved=row(investigation,input.runId(),false);
            if(!hash.equals(saved.get("request_hash")))throw conflict("Run UUID already belongs to different input");
            return get(investigation,input.runId());
        }
        evidence.resolve(investigation,input.evidence());
        transactions.execute(status->{
            var reports=jdbc.queryForList("SELECT id,snapshot_id,title,bug_description,expected_behavior,observed_behavior FROM investigations WHERE id=? FOR UPDATE",investigation);
            if(reports.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Investigation not found");
            var report=reports.getFirst();
            // No background coordinator: idle expired runs release their active slot on the next create.
            jdbc.update("UPDATE agent_runs SET state='BUDGET_EXHAUSTED',error_message='Deadline expired',updated_at=clock_timestamp() WHERE investigation_id=? AND state IN ('READY','TOOL_REVIEW') AND deadline<=clock_timestamp()",investigation);
            if(!jdbc.queryForList("SELECT id FROM agent_runs WHERE investigation_id=? AND state IN ('READY','RUNNING','TOOL_REVIEW')",investigation).isEmpty())throw conflict("This investigation already has an active role run; finish it or stop it first");
            var results=new ArrayList<Map<String,Object>>();
            for(UUID id:input.executionIds()) {
                var jobs=jdbc.queryForList("""
                    SELECT j.id,j.operation,j.test_selector,j.result,j.completed_at FROM execution_jobs j
                    JOIN executions e ON e.id=j.id WHERE j.id=? AND e.investigation_id=?
                    AND j.snapshot_id=? AND j.queue_state='COMPLETED' AND j.result IS NOT NULL
                    """,id,investigation,report.get("snapshot_id"));
                if(jobs.isEmpty())throw bad("Execution must be completed and belong to this investigation and snapshot");
                var job=jobs.getFirst();var metadata=json.readTree(job.get("result").toString());
                var item=new LinkedHashMap<String,Object>();
                item.put("executionId",id);item.put("operation",job.get("operation"));item.put("selectedTest",job.get("test_selector"));
                item.put("classification",metadata.path("classification").asString("UNAVAILABLE"));
                item.put("exitCode",metadata.get("exitCode"));item.put("completedAt",String.valueOf(job.get("completed_at")));
                item.put("scope","Existing recorded execution; not a generated-patch verification");results.add(item);
            }
            String reportJson=settings.redact(json.writeValueAsString(report));
            if(reportJson.length()>6000)throw bad("Report exceeds 6000 characters for this role workflow");
            jdbc.update("""
                INSERT INTO agent_runs(id,investigation_id,snapshot_id,request_hash,max_calls,max_output_tokens,deadline,report,evidence_refs,execution_results)
                VALUES (?,?,?,?,?,?,clock_timestamp()+(? * INTERVAL '1 second'),CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb))
                """,input.runId(),investigation,report.get("snapshot_id"),hash,input.maxCalls(),input.maxOutputTokens(),input.deadlineSeconds(),reportJson,json.writeValueAsString(input.evidence()),json.writeValueAsString(results));
            return null;
        });
        return get(investigation,input.runId());
    }
    public Map<String,Object> stop(UUID investigation,UUID run) {
        row(investigation,run,false);
        int changed=jdbc.update("UPDATE agent_runs SET state='INTERRUPTED',error_message='Stopped between roles by user',updated_at=clock_timestamp() WHERE id=? AND state IN ('READY','TOOL_REVIEW')",run);
        if(changed==0)throw conflict("Stop is available only between roles; an in-flight model call is not cancelled");
        return get(investigation,run);
    }
    public Map<String,Object> finishVerification(UUID investigation,UUID run) {
        transactions.execute(status->{
            var saved=row(investigation,run,true);
            if(!"TOOL_REVIEW".equals(saved.get("state")))throw conflict("Run is not waiting for verification-tool review");
            if(instant(saved.get("deadline")).isBefore(Instant.now())) {
                jdbc.update("UPDATE agent_runs SET state='BUDGET_EXHAUSTED',updated_at=clock_timestamp() WHERE id=?",run);return null;
            }
            tools.refreshExecutions(investigation,run,saved);
            int summaries=jdbc.queryForObject("""
                SELECT count(*) FROM role_tool_calls t WHERE run_id=? AND role='VERIFICATION_AGENT'
                AND tool='createVerificationSummary' AND state='SUCCEEDED'
                AND created_at>(SELECT max(created_at) FROM role_tool_calls WHERE run_id=? AND execution_id IS NOT NULL)
                """,Integer.class,run,run);
            if(summaries<1)throw conflict("Read completed reports and create a verification summary after the latest requested check before finishing");
            jdbc.update("UPDATE agent_runs SET state=?,next_role=5,updated_at=clock_timestamp() WHERE id=?",settings.provider().equals("mock")?"MOCK_COMPLETE":"REVIEW_REQUIRED",run);
            return null;
        });
        return get(investigation,run);
    }
    public Map<String,Object> advance(UUID investigation,UUID run,int expectedRole) {
        Map<String,Object> reserved=transactions.execute(status->{
            var saved=row(investigation,run,true);
            if(!"READY".equals(saved.get("state")))throw conflict("Run is not ready. Load its saved status; no automatic retry is sent");
            int next=((Number)saved.get("next_role")).intValue();
            if(next!=expectedRole)throw conflict("Stale role position; load the run before advancing");
            var role=AgentRolePolicy.role(next);
            var deadline=instant(saved.get("deadline"));
            if(!AgentRolePolicy.canReserve(((Number)saved.get("used_calls")).intValue(),((Number)saved.get("max_calls")).intValue(),
                ((Number)saved.get("reserved_output_tokens")).intValue(),((Number)saved.get("max_output_tokens")).intValue(),settings.maxOutput(),Instant.now(),deadline,settings.timeout())) {
                jdbc.update("UPDATE agent_runs SET state='BUDGET_EXHAUSTED',error_message='Call, output-token reservation, or deadline budget exhausted',updated_at=clock_timestamp() WHERE id=?",run);
                return null;
            }
            tools.refreshExecutions(investigation,run,saved);
            if(role==AgentRolePolicy.Role.DIAGNOSIS_AGENT || role==AgentRolePolicy.Role.VERIFICATION_AGENT) {
                var actual=json.readTree(saved.get("execution_results").toString());
                boolean failure=false;for(var item:actual)if("ASSERTION_FAILED".equals(item.path("classification").asString()))failure=true;
                if(actual.isEmpty() || (role==AgentRolePolicy.Role.DIAGNOSIS_AGENT && !failure)) {
                    // Mock can demonstrate role order without pretending execution evidence exists.
                    if(!settings.provider().equals("mock")) {
                        jdbc.update("UPDATE agent_runs SET state='NEEDS_EVIDENCE',error_message='Diagnosis needs a saved assertion failure; verification needs actual saved executions',updated_at=clock_timestamp() WHERE id=?",run);
                        return null;
                    }
                }
            }
            UUID call=UUID.randomUUID();
            jdbc.update("UPDATE agent_runs SET state='RUNNING',used_calls=used_calls+1,reserved_output_tokens=reserved_output_tokens+?,updated_at=clock_timestamp() WHERE id=?",settings.maxOutput(),run);
            jdbc.update("INSERT INTO agent_role_steps(run_id,role_number,role,call_id) VALUES (?,?,?,?)",run,next,role.name(),call);
            var data=new LinkedHashMap<>(saved);data.put("call_id",call);return data;
        });
        if(reserved==null)return get(investigation,run);
        int next=((Number)reserved.get("next_role")).intValue();var role=AgentRolePolicy.role(next);
        UUID callId=(UUID)reserved.get("call_id");
        try {
            var current=jdbc.queryForMap("SELECT snapshot_id FROM investigations WHERE id=?",investigation);
            if(!Objects.equals(current.get("snapshot_id"),reserved.get("snapshot_id")))throw new IllegalArgumentException("Investigation snapshot changed; create a new role run");
            var refs=new ArrayList<AiEvidenceService.Ref>();
            for(var item:json.readTree(reserved.get("evidence_refs").toString()))refs.add(json.treeToValue(item,AiEvidenceService.Ref.class));
            var handoffs=new ArrayList<Map<String,Object>>();
            for(var step:jdbc.queryForList("SELECT role,call_id,output FROM agent_role_steps WHERE run_id=? AND state='VALIDATED' ORDER BY role_number",run)) {
                String summary=json.readTree(step.get("output").toString()).get("summary").asString();
                handoffs.add(Map.of("role",step.get("role"),"callId",step.get("call_id"),"summaryExcerpt",summary.substring(0,Math.min(500,summary.length())),"trust","Unverified model proposal, not a test result"));
            }
            var input=new LinkedHashMap<String,Object>();input.put("report",json.readTree(reserved.get("report").toString()));
            input.put("previousRoleProposals",handoffs);input.put("actualRecordedExecutions",json.readTree(reserved.get("execution_results").toString()));
            input.put("roleTools",RoleToolPolicy.tools(role));input.put("executionToolsEnabled",reserved.get("execution_tools_enabled"));
            input.put("toolResults",tools.handoff(investigation,run));
            var call=calls.submitRole(investigation,new AiCallService.Submission(callId,json.writeValueAsString(input),refs),role);
            if(!"SUCCEEDED".equals(call.get("state")))throw new IllegalArgumentException("Model call did not complete. Inspect saved call "+callId);
            var ids=new HashSet<String>();for(var item:json.readTree(json.writeValueAsString(call.get("input_evidence"))))ids.add(item.get("id").asString());
            var output=AgentRoleOutput.parse((String)call.get("response_text"),role,ids);
            String state=(next==4 ? (settings.provider().equals("mock")?"MOCK_COMPLETE":"REVIEW_REQUIRED") : "READY");
            if(!settings.provider().equals("mock") && output.evidenceIds().isEmpty())state="NEEDS_EVIDENCE";
            int toolIndex=0;
            for(var request:output.toolRequests()) {
                var audited=tools.model(investigation,run,callId,next,toolIndex++,request.tool(),request.arguments());
                if(!"SUCCEEDED".equals(audited.get("state")))state="NEEDS_EVIDENCE";
            }
            if(next==4 && jdbc.queryForObject("SELECT count(*) FROM role_tool_calls WHERE run_id=? AND role_number=4 AND execution_id IS NOT NULL",Integer.class,run)>0
                && !"NEEDS_EVIDENCE".equals(state))state="TOOL_REVIEW";
            String finalState=state;int nextRole=state.equals("TOOL_REVIEW")?4:next+1;
            transactions.execute(status->{
                jdbc.update("UPDATE agent_role_steps SET state='VALIDATED',output=CAST(? AS jsonb),finished_at=clock_timestamp() WHERE run_id=? AND role_number=?",json.writeValueAsString(output),run,next);
                jdbc.update("UPDATE agent_runs SET state=CASE WHEN deadline<clock_timestamp() THEN 'BUDGET_EXHAUSTED' ELSE ? END,next_role=?,updated_at=clock_timestamp() WHERE id=? AND state='RUNNING'",finalState,nextRole,run);
                return null;
            });
        } catch(Exception ex) {
            // No raw exception or provider body is persisted; the model-call audit has safe details.
            jdbc.update("UPDATE agent_role_steps SET state='FAILED',error_message='Role failed validation or model integration; inspect saved call',finished_at=clock_timestamp() WHERE run_id=? AND role_number=?",run,next);
            jdbc.update("UPDATE agent_runs SET state='FAILED',error_message='Role failed. Reservation retained; no automatic retry. Inspect saved role call.',updated_at=clock_timestamp() WHERE id=? AND state='RUNNING'",run);
        }
        return get(investigation,run);
    }
    private Map<String,Object> row(UUID investigation,UUID run,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM agent_runs WHERE id=? AND investigation_id=?"+(lock?" FOR UPDATE":""),run,investigation);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Role run not found for this investigation");return rows.getFirst();
    }
    @EventListener(ApplicationReadyEvent.class) public void recover() {
        // Single backend deployment only. A possibly billed request is never silently replayed.
        jdbc.update("UPDATE agent_role_steps SET state='INTERRUPTED',error_message='Backend restarted during role; inspect saved call before deciding next action' WHERE state='RESERVED'");
        jdbc.update("UPDATE agent_runs SET state='INTERRUPTED',error_message='Backend restarted during role. No automatic retry was sent.',updated_at=clock_timestamp() WHERE state='RUNNING'");
    }
    private static Instant instant(Object value) {
        if(value instanceof OffsetDateTime date)return date.toInstant();
        if(value instanceof java.sql.Timestamp date)return date.toInstant();
        throw new IllegalArgumentException("Unsupported database timestamp type");
    }
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
