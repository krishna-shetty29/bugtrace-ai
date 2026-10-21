package com.bugtrace.backend;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkReproductionTest {
    private final JsonMapper json=JsonMapper.builder().build();
    static BenchmarkCatalog.Registration context(){String path="src/main/java/com/bugtrace/benchmark/CheckoutProcessor.java";return new BenchmarkCatalog.Registration(BenchmarkCatalog.VERSION,"1.0.0","a".repeat(40),BenchmarkCatalog.MANIFEST,"public","case-001","cases/case-001","b".repeat(64),List.of(path),"com.bugtrace.benchmark","com.bugtrace.benchmark.GeneratedBenchmarkReproductionTest","src/test/java/com/bugtrace/benchmark/GeneratedBenchmarkReproductionTest.java",Map.of(),Map.of(path,"c".repeat(64)));}
    static String source(){return "package com.bugtrace.benchmark; import org.junit.jupiter.api.Test; import org.junit.jupiter.api.Timeout; import static org.junit.jupiter.api.Assertions.*; class GeneratedBenchmarkReproductionTest { @Test @Timeout(5) void reproduction(){assertEquals(1,2);} }";}
    private String plan(String outcome){return json.writeValueAsString(Map.of("outcome",outcome,"hypothesis","Source-based advisory explanation","evidenceIds",List.of("e1"),"missingEvidence",outcome.equals("NEEDS_EVIDENCE")?List.of("Provider trace"):List.of(),"explanation","Not executed"));}
    @Test void acceptsInvestigate(){assertEquals("INVESTIGATE",BenchmarkReproduction.plan(plan("INVESTIGATE"),Set.of("e1")).outcome());}
    @Test void acceptsNoPatchWithoutGeneratingSource(){assertEquals("NO_PATCH",BenchmarkReproduction.plan(plan("NO_PATCH"),Set.of("e1")).outcome());}
    @Test void acceptsEvidenceRequest(){assertEquals(List.of("Provider trace"),BenchmarkReproduction.plan(plan("NEEDS_EVIDENCE"),Set.of("e1")).missingEvidence());}
    @Test void rejectsUnknownCitation(){assertThrows(IllegalArgumentException.class,()->BenchmarkReproduction.plan(plan("NO_PATCH"),Set.of("other")));}
    @Test void rejectsToolOrPathFields(){assertThrows(IllegalArgumentException.class,()->BenchmarkReproduction.plan(plan("NO_PATCH").replaceFirst("\\{","{\"command\":\"run\","),Set.of("e1")));}
    @Test void rejectsDuplicateJsonKeys(){assertThrows(Exception.class,()->BenchmarkReproduction.plan(plan("NO_PATCH").replaceFirst("\\{","{\"outcome\":\"INVESTIGATE\","),Set.of("e1")));}
    @Test void rejectsTrailingJson(){assertThrows(Exception.class,()->BenchmarkReproduction.plan(plan("NO_PATCH")+"{}",Set.of("e1")));}
    @Test void acceptsRegisteredSyntaxButDoesNotClaimExecution(){assertDoesNotThrow(()->BenchmarkReproduction.validateSource(source(),context()));}
    @Test void rejectsDifferentPackage(){assertThrows(IllegalArgumentException.class,()->BenchmarkReproduction.validateSource(source().replace("package com.bugtrace.benchmark","package elsewhere"),context()));}
    @Test void rejectsDifferentClass(){assertThrows(IllegalArgumentException.class,()->BenchmarkReproduction.validateSource(source().replace("class GeneratedBenchmarkReproductionTest","class ArbitraryTest"),context()));}
    @Test void rejectsMissingOuterTimeout(){assertThrows(IllegalArgumentException.class,()->BenchmarkReproduction.validateSource(source().replace("@Timeout(5)",""),context()));}
    @Test void rejectsLargeTimeout(){assertThrows(IllegalArgumentException.class,()->BenchmarkReproduction.validateSource(source().replace("@Timeout(5)","@Timeout(600)"),context()));}
    @Test void rejectsDisabledTest(){assertThrows(IllegalArgumentException.class,()->BenchmarkReproduction.validateSource(source().replace("@Test","@Disabled @Test"),context()));}
    @Test void rejectsAssumptions(){assertThrows(IllegalArgumentException.class,()->BenchmarkReproduction.validateSource(source().replace("assertEquals(1,2)","assumeTrue(false)"),context()));}
    @Test void executionDraftIsNeverQueued(){var c=context();var spec=new BenchmarkReproduction.Spec(source(),"Expected value","Controlled scenario",List.of("e1"),"Assertion failure","Proposal only");var draft=BenchmarkReproduction.executionDraft(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),c,spec);assertEquals(false,draft.get("queued"));assertEquals(false,draft.get("executionSupported"));assertEquals(c.generatedTestPath(),((Map<?,?>)draft.get("generatedTest")).get("path"));}
    @Test void rejectsCrossCaseOrHistoricalEvidence(){var c=context();var e=new AiEvidenceService.Evidence("e1",null,"HISTORICAL_DIFF","../bbh/private.java",c.releaseCommit(),"DIFF_LINE",1,2,"hash","data");assertThrows(IllegalArgumentException.class,()->BenchmarkReproductionService.validateEvidence(c,List.of(e)));}
    @Test void rejectsForeignRevision(){var c=context();var e=new AiEvidenceService.Evidence("e1",null,"CURRENT_SNAPSHOT_CODE",c.allowedProductionPaths().getFirst(),"d".repeat(40),"SOURCE_LINE",1,2,"hash","data");assertThrows(IllegalArgumentException.class,()->BenchmarkReproductionService.validateEvidence(c,List.of(e)));}
    @Test void mockIsExplicitUnscoredAbstention()throws Exception {var response=new MockAiProvider().generate(new AiProvider.Request("mock-v1",BenchmarkReproduction.PLAN_INSTRUCTIONS,"{\"evidence\":[{\"id\":\"e1\"}]}",1500));assertEquals("NEEDS_EVIDENCE",BenchmarkReproduction.plan(response.text(),Set.of("e1")).outcome());assertEquals("MOCK_NOT_MEASURED",response.usage().kind());}
    @Test void jdbcTimestampPreservesDeadlineInstant() {
        var expected=java.time.Instant.parse("2026-10-19T10:00:00Z");
        assertEquals(expected,BenchmarkReproductionService.deadlineInstant(java.sql.Timestamp.from(expected)));
    }
    @Test void offsetDeadlineUsesAbsoluteTime() {
        var deadline=java.time.OffsetDateTime.parse("2026-10-19T15:30:00+05:30");
        assertEquals(java.time.Instant.parse("2026-10-19T10:00:00Z"),BenchmarkReproductionService.deadlineInstant(deadline));
    }
    @Test void instantDeadlineRemainsUnchanged() {
        var deadline=java.time.Instant.parse("2020-01-01T00:00:00Z");
        assertEquals(deadline,BenchmarkReproductionService.deadlineInstant(deadline));
    }
    @Test void invalidDeadlineFailsClosed() {
        assertThrows(IllegalArgumentException.class,()->BenchmarkReproductionService.deadlineInstant(null));
        assertThrows(IllegalArgumentException.class,()->BenchmarkReproductionService.deadlineInstant("2099-01-01"));
    }
}
