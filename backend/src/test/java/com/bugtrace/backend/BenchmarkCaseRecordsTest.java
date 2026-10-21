package com.bugtrace.backend;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkCaseRecordsTest {
    @TempDir Path base;
    final String path="src/main/java/com/bugtrace/benchmark/CheckoutProcessor.java";
    BenchmarkRecordScope.Context context() throws Exception {
        Path file=base.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,"class CheckoutProcessor {}\n");
        var c=BenchmarkReproductionTest.context();
        return new BenchmarkRecordScope.Context(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"d".repeat(64),base,c);
    }
    AiEvidenceService.Evidence item(String origin,String source,String revision,String text) {
        return new AiEvidenceService.Evidence("e1",new AiEvidenceService.Ref("CODE",UUID.randomUUID(),"key",null),origin,source,revision,"SOURCE_LINE",1,1,ContextDocuments.hash(text),text);
    }
    @Test void identityCarriesCaseRunAndBothSnapshotHashes() throws Exception {
        var c=context();UUID run=UUID.randomUUID();var id=BenchmarkCaseRecordsService.identity(c,run);
        assertEquals(c.registration().collectionVersion(),id.get("collectionVersion"));assertEquals("case-001",id.get("caseId"));
        assertEquals(run,id.get("runId"));assertEquals(c.snapshotId(),id.get("snapshotId"));assertEquals(c.contentHash(),id.get("snapshotContentHash"));assertEquals(c.registration().snapshotContentHash(),id.get("benchmarkContentHash"));
    }
    @Test void acceptsExactCapturedEvidence()throws Exception {var c=context();assertDoesNotThrow(()->BenchmarkRecordScope.validateEvidence(c,item("CURRENT_SNAPSHOT_CODE",path,c.registration().releaseCommit(),"class CheckoutProcessor {}\n")));}
    @Test void rejectsPrivateAndOtherCasePaths()throws Exception {var c=context();for(String p:List.of("../bbh/private.java","C:/Users/ADMIN/bbv/log.txt","cases/case-002/Secret.java"))assertThrows(Exception.class,()->BenchmarkRecordScope.validateEvidence(c,item("CURRENT_SNAPSHOT_CODE",p,c.registration().releaseCommit(),"secret\n")));}
    @Test void rejectsHistoricalAndContextOrigins()throws Exception {var c=context();for(String origin:List.of("HISTORICAL_DIFF","CONTEXT","EXECUTION_STDOUT"))assertThrows(Exception.class,()->BenchmarkRecordScope.validateEvidence(c,item(origin,path,c.registration().releaseCommit(),"class CheckoutProcessor {}\n")));}
    @Test void rejectsPoisonedTextEvenWithMatchingSelfHash()throws Exception {var c=context();assertThrows(Exception.class,()->BenchmarkRecordScope.validateEvidence(c,item("CURRENT_SNAPSHOT_CODE",path,c.registration().releaseCommit(),"Fabricated assertion output\n")));}
    @Test void rejectsEvidenceFromOtherRevision()throws Exception {var c=context();assertThrows(Exception.class,()->BenchmarkRecordScope.validateEvidence(c,item("CURRENT_SNAPSHOT_CODE",path,"f".repeat(40),"class CheckoutProcessor {}\n")));}
    @Test void runSelectionMustContainEveryRequestedReference() {
        var own=new AiEvidenceService.Ref("CODE",UUID.randomUUID(),"one",null);var other=new AiEvidenceService.Ref("CODE",UUID.randomUUID(),"two",null);
        assertDoesNotThrow(()->BenchmarkRecordScope.selectedReferences(List.of(own),List.of(own)));
        assertThrows(Exception.class,()->BenchmarkRecordScope.selectedReferences(List.of(other),List.of(own)));
        assertThrows(Exception.class,()->BenchmarkRecordScope.selectedReferences(List.of(own,own),List.of(own)));
    }
    @Test void missingExecutionAndRepairCountersAreNotRecordedAsZero() {
        var row=new HashMap<String,Object>();row.put("deadline",java.sql.Timestamp.from(Instant.parse("2026-10-19T10:00:00Z")));row.put("calls",1);
        var budget=BenchmarkCaseRecordsService.budgets(row);assertEquals(1,budget.get("calls"));assertNull(budget.get("executionBudget"));assertNull(budget.get("repairBudget"));assertEquals("2026-10-19T10:00:00Z",budget.get("deadline"));
    }
    @Test void noPatchRemainsAdvisoryAndCannotAuthorizeChanges() {
        var row=Map.<String,Object>of("state","FINISHED_NO_PATCH","plan","{\"outcome\":\"NEEDS_EVIDENCE\"}");var result=BenchmarkCaseRecordsService.conclusion(row);
        assertEquals("ADVISORY_NO_PATCH",result.get("status"));assertEquals(false,result.get("verifiedByExecution"));assertEquals(false,result.get("changesAuthorized"));
    }
    @Test void testDraftDoesNotEstablishFinalVerification() {var result=BenchmarkCaseRecordsService.conclusion(Map.of("state","TEST_READY"));assertEquals("NO_FINAL_BENCHMARK_CONCLUSION",result.get("status"));assertEquals(false,result.get("verifiedByExecution"));}
}
