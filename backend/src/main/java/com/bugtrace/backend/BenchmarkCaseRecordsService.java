package com.bugtrace.backend;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Append-only exports of existing facts. This service never invents executions or verified conclusions. */
@Service
public class BenchmarkCaseRecordsService {
    public record Search(String query,SnapshotSearchEngine.Kind kind,int limit) {}
    public record EvidenceRequest(List<AiEvidenceService.Ref> evidence) {}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final BenchmarkRecordScope scope;
    private final AiEvidenceService evidence;private final SnapshotSearchService search;
    private BenchmarkExecutionService executions;
    private final JsonMapper json=JsonMapper.builder().build();
    public BenchmarkCaseRecordsService(JdbcTemplate jdbc,PlatformTransactionManager manager,BenchmarkRecordScope scope,AiEvidenceService evidence,SnapshotSearchService search) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.scope=scope;this.evidence=evidence;this.search=search;
    }
    @org.springframework.beans.factory.annotation.Autowired
    public BenchmarkCaseRecordsService(JdbcTemplate jdbc,PlatformTransactionManager manager,BenchmarkRecordScope scope,AiEvidenceService evidence,SnapshotSearchService search,BenchmarkExecutionService executions) {
        this(jdbc,manager,scope,evidence,search);this.executions=executions;
    }
    public Map<String,Object> export(UUID investigation,UUID run) {
        var c=scope.required(investigation);
        return tx.execute(status->{var row=scope.run(c,run,true);if("BUSY".equals(row.get("state")))throw BenchmarkRecordScope.reject("Wait for the model step before exporting a stable record");
            var document=document(c,run,row);return save(c,run,"EXPORT",document);});
    }
    public List<Map<String,Object>> list(UUID investigation,UUID run) {
        var c=scope.required(investigation);scope.run(c,run,false);
        return jdbc.queryForList("SELECT id,kind,document_sha256,created_at FROM benchmark_case_records WHERE run_id=? AND investigation_id=? ORDER BY created_at DESC,id LIMIT 100",run,investigation);
    }
    public Map<String,Object> get(UUID investigation,UUID run,UUID record) {
        var c=scope.required(investigation);scope.run(c,run,false);
        var rows=jdbc.queryForList("SELECT * FROM benchmark_case_records WHERE id=? AND run_id=? AND investigation_id=?",record,run,investigation);
        if(rows.isEmpty())throw missing("Record does not belong to this run");var row=rows.getFirst();var document=json.readTree(row.get("document").toString());
        if(!c.snapshotId().equals(row.get("snapshot_id"))||!c.contentHash().equals(row.get("content_hash"))
            ||!c.registration().caseId().equals(row.get("case_id"))||!c.registration().collectionVersion().equals(row.get("collection_version"))
            ||!DiagnosisIntegrity.hash(document).equals(row.get("document_sha256"))
            ||!DiagnosisIntegrity.hash(identity(c,run)).equals(DiagnosisIntegrity.hash(document.get("identity"))))throw BenchmarkRecordScope.reject("Saved record integrity or ownership mismatch");
        return Map.of("recordId",record,"kind",row.get("kind"),"documentSha256",row.get("document_sha256"),"document",document);
    }
    public Map<String,Object> retrieve(UUID investigation,UUID run,Search request) {
        if(request==null)throw BenchmarkRecordScope.reject("Supply a bounded search request");
        SnapshotSearchEngine.Query.create(request.query(),request.kind(),request.limit());
        var c=scope.required(investigation);
        var row=tx.execute(status->{var reserved=scope.run(c,run,true);int count=((Number)reserved.get("retrieval_requests")).intValue();
            if(count>=30)throw BenchmarkRecordScope.reject("Run retrieval budget exhausted (30 requests)");
            jdbc.update("UPDATE benchmark_reproduction_runs SET retrieval_requests=retrieval_requests+1 WHERE id=?",run);
            var copy=new LinkedHashMap<>(reserved);copy.put("retrieval_requests",count+1);return copy;});
        var results=search.search(investigation,request.query(),request.kind(),request.limit());
        if(!investigation.equals(results.investigationId())||!c.snapshotId().equals(results.snapshotId())||!c.contentHash().equals(results.contentHash()))throw BenchmarkRecordScope.reject("Retrieval snapshot differs from run");
        for(var hit:results.hits())if(!c.registration().files().containsKey(hit.filePath())||!hit.executionId().isEmpty())throw BenchmarkRecordScope.reject("Retrieval contains unbound evidence");
        var doc=new LinkedHashMap<String,Object>();doc.put("identity",identity(c,run));doc.put("results",results);
        doc.put("evidenceReferences",json.readTree(row.get("evidence_refs").toString()));doc.put("budgetCounters",budgets(row));
        return tx.execute(status->{scope.run(c,run,true);return save(c,run,"RETRIEVAL",doc);});
    }
    public List<AiEvidenceService.Evidence> resolve(UUID investigation,UUID run,EvidenceRequest request) {
        var c=scope.required(investigation);var row=scope.run(c,run,false);var selected=refs(row);
        BenchmarkRecordScope.selectedReferences(request==null?null:request.evidence(),selected);
        return evidence.resolve(investigation,request.evidence());
    }
    public Map<String,Object> modelCall(UUID investigation,UUID run,UUID call) {
        var c=scope.required(investigation);scope.run(c,run,false);return call(c,run,call);
    }
    public Map<String,Object> source(UUID investigation,UUID run,int version) {
        var c=scope.required(investigation);scope.run(c,run,false);
        var rows=jdbc.queryForList("SELECT * FROM benchmark_source_versions WHERE run_id=? AND version=?",run,version);
        if(rows.isEmpty())throw missing("Source version not saved for this run; export the record after generation first");
        var row=rows.getFirst();var spec=json.treeToValue(json.readTree(row.get("source_document").toString()),BenchmarkReproduction.Spec.class);
        if(!ContextDocuments.hash(spec.testSource()).equals(row.get("source_sha256")))throw BenchmarkRecordScope.reject("Source version hash mismatch");
        call(c,run,(UUID)row.get("model_call_id"));BenchmarkReproduction.validateSource(spec.testSource(),c.registration());
        return Map.of("identity",identity(c,run),"version",version,"sourceSha256",row.get("source_sha256"),"modelCallId",row.get("model_call_id"),"sourceDocument",spec,"executed",wasExecuted(investigation,run,version));
    }
    private Map<String,Object> document(BenchmarkRecordScope.Context c,UUID run,Map<String,Object> row) {
        var selected=evidence.resolve(c.investigationId(),refs(row));
        var savedIds=json.readTree(row.get("evidence_ids").toString());
        if(!DiagnosisIntegrity.hash(selected.stream().map(AiEvidenceService.Evidence::id).toList()).equals(DiagnosisIntegrity.hash(savedIds)))throw BenchmarkRecordScope.reject("Saved evidence identities differ");
        var steps=jdbc.queryForList("SELECT id,action,call_id,state,error FROM benchmark_reproduction_steps WHERE run_id=? ORDER BY created_at,id",run);
        var calls=new ArrayList<Map<String,Object>>();
        for(var step:steps) {
            if(step.get("call_id")==null)continue;
            UUID id=(UUID)step.get("call_id");
            if(jdbc.queryForObject("SELECT count(*) FROM ai_model_calls WHERE id=?",Integer.class,id)==0)calls.add(Map.of("id",id,"state","NOT_PERSISTED","stepId",step.get("id")));
            else calls.add(call(c,run,id));
        }
        var result=new LinkedHashMap<String,Object>();result.put("recordVersion","benchmark-case-record-v1");result.put("identity",identity(c,run));
        result.put("provider",row.get("provider"));result.put("model",row.get("model"));result.put("mock",row.get("provider").equals("mock"));
        result.put("state",row.get("state"));result.put("modelCalls",calls);result.put("steps",steps);
        result.put("evidenceReferences",selected);result.put("budgetCounters",budgets(row));
        result.put("generatedSource",saveSource(c,run,row,steps));
        var linked=executions==null?List.<Map<String,Object>>of():executions.list(c.investigationId(),run);
        result.put("executions",linked);result.put("executionIds",linked.stream().map(e->e.get("executionId")).toList());result.put("executionStatus",linked.isEmpty()?"NONE_EXECUTED": "RECORDED");
        result.put("unboundExecutionCount",executions==null?0:jdbc.queryForObject("SELECT count(*) FROM executions e WHERE investigation_id=? AND NOT EXISTS (SELECT 1 FROM benchmark_execution_links l WHERE l.execution_id=e.id)",Integer.class,c.investigationId()));
        var conclusion=new LinkedHashMap<>(conclusion(row));conclusion.put("executionObservations",linked.stream().map(e->Map.of("executionId",e.get("executionId"),"observation",e.get("observation"))).toList());
        result.put("finalConclusion",conclusion);result.put("executionSupported",executions!=null);
        return result;
    }
    private Object saveSource(BenchmarkRecordScope.Context c,UUID run,Map<String,Object> row,List<Map<String,Object>> steps) {
        if(row.get("source_document")==null)return null;
        var spec=json.treeToValue(json.readTree(row.get("source_document").toString()),BenchmarkReproduction.Spec.class);
        var allowed=new HashSet<String>();for(var item:json.readTree(row.get("evidence_ids").toString()))allowed.add(item.asText());
        // Reapply the complete source and citation validator before making a version record.
        var checked=BenchmarkReproduction.parse(json.writeValueAsString(spec),allowed,c.registration());
        String hash=ContextDocuments.hash(checked.testSource());
        var draft=json.readTree(row.get("execution_draft").toString());
        if(!hash.equals(draft.path("generatedTest").path("sourceSha256").asText())||!checked.testSource().equals(draft.path("generatedTest").path("source").asText()))throw BenchmarkRecordScope.reject("Draft source hash differs from saved proposal");
        var generated=steps.stream().filter(s->Set.of("GENERATE_TEST","REPAIR_TEST").contains(s.get("action"))&&"SUCCEEDED".equals(s.get("state"))).toList();
        int version=generated.size();if(version<1||version>3)throw BenchmarkRecordScope.reject("Invalid generated source version count");
        UUID modelCall=(UUID)generated.getLast().get("call_id");call(c,run,modelCall);
        jdbc.update("INSERT INTO benchmark_source_versions(run_id,version,model_call_id,source_sha256,source_document) VALUES (?,?,?,?,CAST(? AS jsonb)) ON CONFLICT(run_id,version) DO NOTHING",run,version,modelCall,hash,json.writeValueAsString(checked));
        var saved=jdbc.queryForMap("SELECT model_call_id,source_sha256,source_document FROM benchmark_source_versions WHERE run_id=? AND version=?",run,version);
        if(!modelCall.equals(saved.get("model_call_id"))||!hash.equals(saved.get("source_sha256"))||!DiagnosisIntegrity.hash(checked).equals(DiagnosisIntegrity.hash(json.readTree(saved.get("source_document").toString()))))throw BenchmarkRecordScope.reject("Saved source version changed; versions cannot be overwritten");
        return Map.of("version",version,"sourceSha256",hash,"modelCallId",modelCall,"testPath",c.registration().generatedTestPath(),"className",c.registration().generatedTestClass(),"sourceDocument",checked,"executed",wasExecuted(c.investigationId(),run,version));
    }
    private boolean wasExecuted(UUID investigation,UUID run,int version) {
        if(executions==null)return false;
        return executions.list(investigation,run).stream().anyMatch(e->((Number)e.get("sourceVersion")).intValue()==version && json.valueToTree(e.get("result")).path("junit").path("tests").asInt()>0);
    }
    private Map<String,Object> call(BenchmarkRecordScope.Context c,UUID run,UUID call) {
        var rows=jdbc.queryForList("SELECT a.id,a.investigation_id,a.snapshot_id,a.provider,a.requested_model,a.actual_model,a.prompt_version,a.state,a.usage_kind,a.input_tokens,a.output_tokens,a.total_tokens,a.duration_ms,a.response_text,a.input_evidence FROM ai_model_calls a JOIN benchmark_reproduction_steps s ON s.call_id=a.id WHERE s.run_id=? AND a.id=? AND a.investigation_id=? AND a.snapshot_id=?",run,call,c.investigationId(),c.snapshotId());
        if(rows.size()!=1)throw missing("Model call does not belong to this case run");var row=new LinkedHashMap<>(rows.getFirst());
        if(!row.get("prompt_version").toString().startsWith(BenchmarkReproduction.VERSION+":"))throw BenchmarkRecordScope.reject("Model call prompt differs from benchmark workflow");
        row.put("input_evidence",json.readTree(row.get("input_evidence").toString()));return row;
    }
    private Map<String,Object> save(BenchmarkRecordScope.Context c,UUID run,String kind,Map<String,Object> document) {
        if(json.writeValueAsString(document).getBytes(StandardCharsets.UTF_8).length>524288)throw BenchmarkRecordScope.reject("Case record exceeds 512 KiB");
        String hash=DiagnosisIntegrity.hash(document);UUID id=UUID.nameUUIDFromBytes((run+":"+kind+":"+hash).getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO benchmark_case_records(id,run_id,investigation_id,snapshot_id,content_hash,collection_version,case_id,kind,document,document_sha256) VALUES (?,?,?,?,?,?,?,?,CAST(? AS jsonb),?) ON CONFLICT DO NOTHING",id,run,c.investigationId(),c.snapshotId(),c.contentHash(),c.registration().collectionVersion(),c.registration().caseId(),kind,json.writeValueAsString(document),hash);
        return get(c.investigationId(),run,id);
    }
    private List<AiEvidenceService.Ref> refs(Map<String,Object> row) {var result=new ArrayList<AiEvidenceService.Ref>();for(var item:json.readTree(row.get("evidence_refs").toString()))result.add(json.treeToValue(item,AiEvidenceService.Ref.class));return result;}
    static Map<String,Object> identity(BenchmarkRecordScope.Context c,UUID run) {
        return Map.of("collectionVersion",c.registration().collectionVersion(),"caseId",c.registration().caseId(),"registrationId",c.registrationId(),"releaseCommit",c.registration().releaseCommit(),"investigationId",c.investigationId(),"runId",run,"snapshotId",c.snapshotId(),"snapshotContentHash",c.contentHash(),"benchmarkContentHash",c.registration().snapshotContentHash());
    }
    static Map<String,Object> budgets(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();for(String key:List.of("calls","max_calls","output_per_call","reserved_output_tokens","max_output_tokens","retrieval_requests"))result.put(key,row.get(key));
        result.put("max_retrieval_requests",30);
        result.put("deadline",InvestigationLoopPolicy.instant(row.get("deadline")).toString());
        result.put("executionBudget",row.containsKey("experiment_jobs")?Map.of("used",row.get("experiment_jobs"),"maximum",3):null);result.put("repairBudget",row.containsKey("compiler_repairs")?Map.of("used",row.get("compiler_repairs"),"maximum",2):null);result.put("executionBudgetStatus",row.containsKey("experiment_jobs")?"ENFORCED":"LEGACY_TEST_SCHEMA");return result;
    }
    static Map<String,Object> conclusion(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();result.put("verifiedByExecution",false);result.put("changesAuthorized",false);
        result.put("status","FINISHED_NO_PATCH".equals(row.get("state"))?"ADVISORY_NO_PATCH":"NO_FINAL_BENCHMARK_CONCLUSION");
        result.put("message",row.get("message"));result.put("plan",row.get("plan")==null?null:JsonMapper.builder().build().readTree(row.get("plan").toString()));return result;
    }
    private static ResponseStatusException missing(String message){return new ResponseStatusException(HttpStatus.NOT_FOUND,message);}
}
