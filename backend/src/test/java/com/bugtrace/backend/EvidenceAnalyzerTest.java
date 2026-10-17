package com.bugtrace.backend;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceAnalyzerTest {
    static class Database extends JdbcTemplate {
        boolean saved;
        @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
            if (sql.contains("ai_model_calls")) return saved ? List.of(Map.of("id",args[0])) : List.of();
            return List.of(Map.of("title","Checkout retry", "bug_description","Response is lost",
                "expected_behavior","One charge", "observed_behavior","Two charges"));
        }
    }
    static class Calls extends AiCallService {
        Map<String,Object> stored;
        int submissions;
        String report;
        Calls() { super(null,null,null,null); }
        @Override Map<String,Object> submitHypothesis(UUID investigation, Submission submission) {
            submissions++; report = submission.question();
            stored = new LinkedHashMap<>();
            stored.put("id",submission.clientRequestId()); stored.put("provider","mock");
            stored.put("prompt_version",EvidenceAnalyzerController.VERSION); stored.put("state","SUCCEEDED");
            stored.put("input_evidence",List.of());
            try { stored.put("response_text",new MockAiProvider().generate(new AiProvider.Request("mock-v1",EvidenceAnalyzerController.INSTRUCTIONS,"{}",1500)).text()); }
            catch (Exception ex) { throw new AssertionError(ex); }
            return stored;
        }
        @Override public Map<String,Object> get(UUID investigation, UUID call) { return stored; }
    }
    @Test void includesSavedReportAndLoadsWithoutAnotherProviderSubmission() {
        var database = new Database(); var calls = new Calls();
        var analyzer = new EvidenceAnalyzerController(calls,database);
        UUID investigation = UUID.randomUUID(), request = UUID.randomUUID();
        var input = new EvidenceAnalyzerController.Submission(request,List.of());
        var first = analyzer.analyze(investigation,input);
        assertEquals("STRUCTURE_VALID",first.get("analysisState"));
        assertEquals(true,first.get("mock"));
        assertTrue(calls.report.contains("One charge")); assertTrue(calls.report.contains("Two charges"));
        database.saved = true;
        assertEquals(first,analyzer.analyze(investigation,input));
        assertEquals(first,analyzer.get(investigation,request));
        assertEquals(1,calls.submissions);
        var changed = new EvidenceAnalyzerController.Submission(request,List.of(new AiEvidenceService.Ref("CODE",UUID.randomUUID(),"different",null)));
        assertThrows(ResponseStatusException.class,()->analyzer.analyze(investigation,changed));
    }
    @Test void invalidAndIncompleteOutputCannotBecomeValidHypothesis() {
        var database = new Database(); var calls = new Calls();
        var analyzer = new EvidenceAnalyzerController(calls,database);
        UUID investigation=UUID.randomUUID(), request=UUID.randomUUID();
        analyzer.analyze(investigation,new EvidenceAnalyzerController.Submission(request,List.of()));
        calls.stored.put("response_text","not json");
        assertEquals("INVALID_OUTPUT",analyzer.get(investigation,request).get("analysisState"));
        calls.stored.put("state","INCOMPLETE");
        assertEquals("MODEL_INCOMPLETE",analyzer.get(investigation,request).get("analysisState"));
        assertFalse(analyzer.get(investigation,request).containsKey("analysis"));
    }
}
