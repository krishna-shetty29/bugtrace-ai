package com.bugtrace.backend;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HypothesisOutputTest {
    private static final String GOOD = """
        {"hypothesis":"A retry may create another charge","evidenceIds":["e1"],
         "suggestedExperiment":"Simulate lost response then retry","missingEvidence":[]}
        """;
    @Test void acceptsExactSelectedCitation() {
        assertEquals("e1", HypothesisOutput.parse(GOOD, Set.of("e1")).evidenceIds().getFirst());
    }
    @Test void rejectsInventedCitation() {
        assertThrows(IllegalArgumentException.class, () -> HypothesisOutput.parse(GOOD, Set.of("other")));
    }
    @Test void rejectsMarkdownAndTrailingGarbage() {
        assertThrows(IllegalArgumentException.class, () -> HypothesisOutput.parse("```json\n"+GOOD+"```", Set.of("e1")));
        assertThrows(IllegalArgumentException.class, () -> HypothesisOutput.parse(GOOD+"garbage", Set.of("e1")));
    }
    @Test void rejectsWrongTypesAndExtraFields() {
        assertThrows(IllegalArgumentException.class, () -> HypothesisOutput.parse(GOOD.replace("[\"e1\"]", "\"e1\""), Set.of("e1")));
        assertThrows(IllegalArgumentException.class, () -> HypothesisOutput.parse(GOOD.replace("\"missingEvidence\":[]", "\"missingEvidence\":[],\"command\":\"execute\""), Set.of("e1")));
    }
    @Test void requiresExplicitGapForUncitedAnswer() {
        assertThrows(IllegalArgumentException.class, () -> HypothesisOutput.parse(GOOD.replace("[\"e1\"]", "[]"), Set.of()));
        assertFalse(HypothesisOutput.parse(GOOD.replace("[\"e1\"]", "[]").replace("\"missingEvidence\":[]", "\"missingEvidence\":[\"No execution logs\"]"), Set.of()).missingEvidence().isEmpty());
    }
    @Test void rejectsDuplicateIds() {
        assertThrows(IllegalArgumentException.class, () -> HypothesisOutput.parse(GOOD.replace("[\"e1\"]", "[\"e1\",\"e1\"]"), Set.of("e1")));
    }
    @Test void mockAnalyzerOutputIsExplicitlySynthetic() throws Exception {
        var response = new MockAiProvider().generate(new AiProvider.Request("mock-v1", EvidenceAnalyzerController.INSTRUCTIONS, "{}", 1500));
        assertTrue(HypothesisOutput.parse(response.text(), Set.of()).hypothesis().startsWith("MOCK"));
        assertEquals("MOCK_NOT_MEASURED", response.usage().kind());
    }
}
