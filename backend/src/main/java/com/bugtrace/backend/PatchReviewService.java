package com.bugtrace.backend;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PatchReviewService {
    public record Register(UUID id,UUID patchId,UUID verificationId,UUID diagnosisId){}
    public record Decision(String action,String expectedBundleHash,String reason,boolean evidenceInspected,boolean diffInspected,boolean reproductionInspected,boolean limitationsAcknowledged,boolean acceptSuiteException){}
    private record Loaded(JsonNode report,Map<String,Object> diagnosis,Map<String,Object> summary,String diff,String reproduction,Map<String,String> proposed,String hash,UUID snapshot){}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final DiagnosisService diagnoses;private final SnapshotImportService snapshots;
    private final Path workspace,verificationRoot;private final JsonMapper json=JsonMapper.builder().build();
    public PatchReviewService(JdbcTemplate jdbc,PlatformTransactionManager manager,DiagnosisService diagnoses,SnapshotImportService snapshots,
        @Value("${bugtrace.workspace-root}") String workspace,@Value("${bugtrace.review-verification-root:${user.home}/btv}") String verificationRoot) {
        this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.diagnoses=diagnoses;this.snapshots=snapshots;this.workspace=Path.of(workspace).toAbsolutePath().normalize();this.verificationRoot=Path.of(verificationRoot).toAbsolutePath().normalize();
    }
    public List<Map<String,Object>> list(UUID investigation){requireInvestigation(investigation);return jdbc.queryForList("SELECT id,patch_id,verification_id,state,created_at,reviewed_at FROM developer_patch_reviews WHERE investigation_id=? ORDER BY created_at DESC LIMIT 30",investigation);}
    public Map<String,Object> register(UUID investigation,Register input) {
        if(input==null||input.id()==null||input.patchId()==null||input.verificationId()==null||input.diagnosisId()==null)throw bad("Supply all four UUIDs");
        Loaded loaded=load(investigation,input.patchId(),input.verificationId(),input.diagnosisId());
        var payload=new LinkedHashMap<String,Object>();payload.put("report",loaded.report());payload.put("diagnosis",loaded.diagnosis());payload.put("summary",loaded.summary());payload.put("diff",loaded.diff());payload.put("reproduction",loaded.reproduction());payload.put("proposed",loaded.proposed());
        String encoded=json.writeValueAsString(payload),payloadHash=DiagnosisIntegrity.hash(payload);
        UUID actual=tx.execute(status->{
            jdbc.queryForMap("SELECT id FROM investigations WHERE id=? FOR UPDATE",investigation);
            var existing=jdbc.queryForList("SELECT * FROM developer_patch_reviews WHERE investigation_id=? AND patch_id=? AND verification_id=?",investigation,input.patchId(),input.verificationId());
            if(!existing.isEmpty()){var row=existing.getFirst();if(!loaded.hash().equals(row.get("bundle_hash")))throw conflict("Existing review binds different bytes. Create a new patch/verification revision.");return (UUID)row.get("id");}
            if(jdbc.queryForObject("SELECT count(*) FROM developer_patch_reviews WHERE id=?",Integer.class,input.id())!=0)throw conflict("Review UUID already exists");
            jdbc.update("INSERT INTO developer_patch_reviews(id,investigation_id,snapshot_id,diagnosis_id,patch_id,verification_id,bundle_hash,payload,payload_hash) VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?)",input.id(),investigation,loaded.snapshot(),input.diagnosisId(),input.patchId(),input.verificationId(),loaded.hash(),encoded,payloadHash);
            event(input.id(),"REGISTERED",Map.of("bundleHash",loaded.hash()));return input.id();
        });return get(investigation,actual);
    }
    public Map<String,Object> get(UUID investigation,UUID id) {
        var row=owned(investigation,id);var payload=json.readTree(row.get("payload").toString());
        if(!DiagnosisIntegrity.hash(payload).equals(row.get("payload_hash")))throw conflict("Saved review payload integrity check failed");
        var result=new LinkedHashMap<String,Object>();for(String field:List.of("id","patch_id","verification_id","diagnosis_id","snapshot_id","bundle_hash","state","decision_reason","suite_exception_accepted","created_at","reviewed_at"))result.put(field,row.get(field));
        result.put("payload",payload);result.put("automaticApplication",false);result.put("events",jdbc.queryForList("SELECT action,details,created_at FROM developer_patch_review_events WHERE review_id=? ORDER BY id",id));return result;
    }
    public Map<String,Object> revalidate(UUID investigation,UUID id) {assertCurrent(investigation,id);event(id,"REVALIDATED",Map.of("automaticApplication",false));return get(investigation,id);}
    public Map<String,Object> decide(UUID investigation,UUID id,Decision input) {
        if(input==null||!Set.of("APPROVE","REJECT").contains(input.action())||input.reason()==null||input.reason().isBlank()||input.reason().length()>2000)throw bad("Choose approve/reject and enter a reason within 2000 characters");
        var current=owned(investigation,id);if(!Objects.equals(current.get("bundle_hash"),input.expectedBundleHash()))throw conflict("Reload the review before deciding");
        if(input.action().equals("APPROVE")) {
            var loaded=assertCurrent(investigation,id);var summary=loaded.summary();
            checkApproval(input,summary);
        }
        tx.execute(status->{
            var row=jdbc.queryForMap("SELECT state,bundle_hash FROM developer_patch_reviews WHERE id=? AND investigation_id=? FOR UPDATE",id,investigation);
            if(!row.get("state").equals("PENDING"))throw conflict("Review already decided; decision history is immutable");
            if(!row.get("bundle_hash").equals(input.expectedBundleHash()))throw conflict("Review bytes changed");
            String state=input.action().equals("APPROVE")?"APPROVED":"REJECTED";
            jdbc.update("UPDATE developer_patch_reviews SET state=?,decision_reason=?,suite_exception_accepted=?,reviewed_at=clock_timestamp() WHERE id=?",state,input.reason(),state.equals("APPROVED")&&input.acceptSuiteException(),id);
            event(id,state,Map.of("reason",input.reason(),"bundleHash",input.expectedBundleHash(),"suiteExceptionAccepted",state.equals("APPROVED")&&input.acceptSuiteException(),"evidenceInspected",input.evidenceInspected(),"diffInspected",input.diffInspected(),"reproductionInspected",input.reproductionInspected(),"limitationsAcknowledged",input.limitationsAcknowledged(),"automaticApplication",false));return null;
        });return get(investigation,id);
    }
    public byte[] download(UUID investigation,UUID id,String kind) {
        var loaded=assertCurrent(investigation,id);
        return switch(kind){case "diff"->loaded.diff().getBytes(StandardCharsets.UTF_8);case "reproduction"->loaded.reproduction().getBytes(StandardCharsets.UTF_8);case "verification"->json.writeValueAsBytes(loaded.report());default->throw bad("Unsupported download");};
    }
    public byte[] export(UUID investigation,UUID id) {
        var loaded=assertCurrent(investigation,id);var saved=get(investigation,id);
        try(var bytes=new ByteArrayOutputStream();var zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)) {
            add(zip,"patch.diff",loaded.diff());add(zip,"verification-report.json",json.writeValueAsString(loaded.report()));add(zip,"diagnosis.json",json.writeValueAsString(loaded.diagnosis()));
            add(zip,"review.json",json.writeValueAsString(saved));add(zip,"reproduction/GeneratedCheckoutReproductionTest.java",loaded.reproduction());
            for(var file:loaded.proposed().entrySet())add(zip,"proposed/"+file.getKey(),file.getValue());
            add(zip,"README.txt","Review export only. This ZIP does not apply, merge or deploy changes. Review state: "+saved.get("state")+". Preserve the immutable snapshot. Full-suite exceptions and unverified behavior are in the verification report.\n");
            zip.finish();byte[] exported=bytes.toByteArray();event(id,"EXPORTED",Map.of("sha256",PatchReviewFiles.sha(exported),"state",saved.get("state"),"automaticApplication",false));return exported;
        }catch(Exception error){throw conflict("Export failed; no repository was modified");}
    }
    private void add(ZipOutputStream zip,String path,String text)throws Exception {zip.putNextEntry(new ZipEntry(path));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
    private Loaded assertCurrent(UUID investigation,UUID id) {
        var row=owned(investigation,id);get(investigation,id);
        var loaded=load(investigation,(UUID)row.get("patch_id"),(UUID)row.get("verification_id"),(UUID)row.get("diagnosis_id"));
        if(!loaded.hash().equals(row.get("bundle_hash")))throw conflict("Evidence or proposal bytes changed; export/approval blocked");return loaded;
    }
    private Loaded load(UUID investigation,UUID patch,UUID verification,UUID diagnosisId) {
        try {
            snapshots.verifySnapshot(investigation);
            var owner=jdbc.queryForMap("SELECT s.id,s.source_location,s.content_hash FROM investigations i JOIN repository_snapshots s ON s.id=i.snapshot_id WHERE i.id=?",investigation);
            Path base=Path.of((String)owner.get("source_location")).toAbsolutePath().normalize();
            if(!base.startsWith(workspace)||!base.getFileName().toString().equals("base"))throw new IllegalArgumentException("Unexpected base location");
            var diagnosis=diagnoses.verify(investigation,diagnosisId);
            if(!"VALID".equals(diagnosis.get("verification_state"))||!owner.get("id").equals(diagnosis.get("snapshot_id")))throw new IllegalArgumentException("Diagnosis evidence does not verify");
            Path patchRoot=base.getParent().resolve("patches").resolve(patch.toString());Path source=patchRoot.resolve("source");Path run=verificationRoot.resolve(verification.toString());
            JsonNode report=json.readTree(PatchReviewFiles.text(PatchReviewFiles.safe(run,"report/verification-report.json"),2_000_000));
            if(!report.path("snapshot").path("id").asString().equals(owner.get("id").toString())||!report.path("snapshot").path("contentHash").asString().equals(owner.get("content_hash"))||!report.path("patch").path("id").asString().equals(patch.toString())||!report.path("verificationId").asString().equals(verification.toString()))throw new IllegalArgumentException("Report identity mismatch");
            JsonNode context=json.readTree(PatchReviewFiles.text(PatchReviewFiles.safe(run,"verification-context.json"),32_000));
            if(!Path.of(context.path("patchRoot").asString()).toAbsolutePath().normalize().equals(patchRoot)||!Path.of(context.path("basePath").asString()).toAbsolutePath().normalize().equals(base))throw new IllegalArgumentException("Verification belongs to a different patch/base");
            for(JsonNode record:report.path("sourceRecords")) {
                Path path=Path.of(record.path("path").asString()).toAbsolutePath().normalize();Path root;
                if(path.startsWith(run))root=run;else if(path.startsWith(patchRoot.resolve("inputs"))||path.startsWith(patchRoot.resolve("artifacts")))root=patchRoot;else throw new IllegalArgumentException("Evidence path outside allowed review inputs");
                path=PatchReviewFiles.safe(root,root.relativize(path).toString().replace('\\','/'));
                if(!PatchReviewFiles.sha(path).equalsIgnoreCase(record.path("sha256").asString()))throw new IllegalArgumentException("Evidence bytes changed");
            }
            String diff=PatchReviewFiles.text(PatchReviewFiles.safe(patchRoot,"artifacts/patch.diff"),256_000);
            if(!PatchReviewFiles.sha(diff.getBytes(StandardCharsets.UTF_8)).equalsIgnoreCase(report.path("patch").path("diffSha256").asString()))throw new IllegalArgumentException("Patch diff hash mismatch");
            var original=new HashMap<String,String>();var proposed=new HashMap<String,String>();
            for(String relative:PatchReviewFiles.ALLOWED){original.put(relative,PatchReviewFiles.text(PatchReviewFiles.safe(base,relative),128_000));proposed.put(relative,PatchReviewFiles.text(PatchReviewFiles.safe(source,relative),128_000));}
            var changed=PatchReviewFiles.validateDiff(diff,original,proposed);proposed.keySet().retainAll(changed);
            // Reject unrelated byte changes or additions, including ignored build outputs. Only .git is local comparison metadata.
            var baseline=new HashSet<String>();try(var tree=Files.walk(base)){for(Path file:tree.filter(Files::isRegularFile).toList()){
                String relative=base.relativize(file).toString().replace('\\','/');baseline.add(relative);
                if(!changed.contains(relative)&&!PatchReviewFiles.sha(PatchReviewFiles.safe(base,relative)).equals(PatchReviewFiles.sha(PatchReviewFiles.safe(source,relative))))throw new IllegalArgumentException("Unrelated source change");}}
            try(var tree=Files.walk(source)){for(Path file:tree.toList()){
                String relative=source.relativize(file).toString().replace('\\','/');if(Files.isSymbolicLink(file))throw new IllegalArgumentException("Linked proposal path");
                if(Files.isRegularFile(file)&&!relative.startsWith(".git/")&&!baseline.contains(relative))throw new IllegalArgumentException("Unexpected proposal file");}}
            String reproduction=PatchReviewFiles.text(PatchReviewFiles.safe(patchRoot,"inputs/GeneratedCheckoutReproductionTest.java.txt"),32_000);
            if(!PatchReviewFiles.sha(reproduction.getBytes(StandardCharsets.UTF_8)).equalsIgnoreCase(report.path("integrity").path("originalReproductionSha256").asString()))throw new IllegalArgumentException("Reproduction hash mismatch");
            var summary=PatchReviewEvidence.validate(report,run,base,source,changed);
            String hash=DiagnosisIntegrity.hash(Map.of("report",report,"diff",diff,"reproduction",reproduction,"proposed",proposed,"diagnosisHash",diagnosis.get("document_hash"),"summary",summary));
            return new Loaded(report,diagnosis,summary,diff,reproduction,proposed,hash,(UUID)owner.get("id"));
        }catch(ResponseStatusException error){throw error;}catch(Exception error){throw conflict("Review inputs failed validation: "+error.getMessage());}
    }
    private void requireInvestigation(UUID investigation){if(jdbc.queryForObject("SELECT count(*) FROM investigations WHERE id=?",Integer.class,investigation)!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Investigation not found");}
    private Map<String,Object> owned(UUID investigation,UUID id){var rows=jdbc.queryForList("SELECT * FROM developer_patch_reviews WHERE id=? AND investigation_id=?",id,investigation);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Review not found in this investigation");return rows.getFirst();}
    private void event(UUID id,String action,Object details){jdbc.update("INSERT INTO developer_patch_review_events(review_id,action,details) VALUES (?,?,CAST(? AS jsonb))",id,action,json.writeValueAsString(details));}
    static void checkApproval(Decision input,Map<String,Object> summary) {
        if(!input.evidenceInspected()||!input.diffInspected()||!input.reproductionInspected()||!input.limitationsAcknowledged())throw bad("Inspect evidence, diff, reproduction and limitations before approval");
        if(!Boolean.TRUE.equals(summary.get("targetedChecksPassed"))||!Boolean.TRUE.equals(summary.get("suiteExceptionRecognized")))throw conflict("Verification contains unresolved unexpected failures");
        if(((Number)summary.get("fullSuiteFailures")).intValue()>0&&!input.acceptSuiteException())throw bad("Explicitly acknowledge the legacy full-suite exception");
    }
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
