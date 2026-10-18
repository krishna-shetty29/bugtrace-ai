package com.bugtrace.backend;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_LOCAL_REVIEW_EVIDENCE_TEST",matches="true")
class PatchReviewLocalEvidenceTest {
    @Test void verifiesActualDiffAndSavedExecutionFiles()throws Exception {
        Path run=Path.of(System.getenv("PATCH_REVIEW_RUN")),base=Path.of(System.getenv("PATCH_REVIEW_BASE")),patch=Path.of(System.getenv("PATCH_REVIEW_PATCH")),reportPath=Path.of(System.getenv("PATCH_REVIEW_REPORT"));
        var json=JsonMapper.builder().build();var report=json.readTree(PatchReviewFiles.text(reportPath,2_000_000));
        var originals=new HashMap<String,String>();var proposed=new HashMap<String,String>();
        for(String path:PatchReviewFiles.ALLOWED){originals.put(path,PatchReviewFiles.text(PatchReviewFiles.safe(base,path),128_000));proposed.put(path,PatchReviewFiles.text(PatchReviewFiles.safe(patch.resolve("source"),path),128_000));}
        Set<String> changed=PatchReviewFiles.validateDiff(PatchReviewFiles.text(PatchReviewFiles.safe(patch,"artifacts/patch.diff"),256_000),originals,proposed);
        assertEquals(2,changed.size());
        var result=PatchReviewEvidence.validate(report,run,base,patch.resolve("source"),changed);
        assertEquals(true,result.get("targetedChecksPassed"));assertEquals(true,result.get("suiteExceptionRecognized"));assertEquals(1,result.get("fullSuiteFailures"));
    }
    @Test void rejectsInventedPassedCountWithoutChangingEvidence()throws Exception {
        var report=JsonMapper.builder().build().readTree(PatchReviewFiles.text(Path.of(System.getenv("PATCH_REVIEW_REPORT")),2_000_000));
        ((ObjectNode)report.path("cases").get(13).path("junit")).put("passed",500);
        assertThrows(IllegalArgumentException.class,()->PatchReviewEvidence.validate(report,Path.of(System.getenv("PATCH_REVIEW_RUN")),Path.of(System.getenv("PATCH_REVIEW_BASE")),Path.of(System.getenv("PATCH_REVIEW_PATCH")).resolve("source"),Set.of(PatchReviewFiles.PREFIX+"PaymentLedger.java",PatchReviewFiles.PREFIX+"FakePaymentGateway.java")));
    }
    @Test void rejectsInventedAssertionTextWithoutChangingEvidence()throws Exception {
        var report=JsonMapper.builder().build().readTree(PatchReviewFiles.text(Path.of(System.getenv("PATCH_REVIEW_REPORT")),2_000_000));
        ((ObjectNode)report.path("cases").get(1).path("reports").get(0).path("testcases").get(0)).put("message","invented failure");
        assertThrows(IllegalArgumentException.class,()->PatchReviewEvidence.validate(report,Path.of(System.getenv("PATCH_REVIEW_RUN")),Path.of(System.getenv("PATCH_REVIEW_BASE")),Path.of(System.getenv("PATCH_REVIEW_PATCH")).resolve("source"),Set.of(PatchReviewFiles.PREFIX+"PaymentLedger.java",PatchReviewFiles.PREFIX+"FakePaymentGateway.java")));
    }
}
