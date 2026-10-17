package com.bugtrace.backend;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ReproductionOutcomeTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private Map<String,Object> metadata(String classification) {
        return new LinkedHashMap<>(Map.of("classification",classification,"snapshotVerifiedAfter",true,"generatedTest",Map.of("installedSourceSha256","hash","unchangedAfterRun",true),"stages",List.of(Map.of("goals",List.of("test-compile"),"exitCode",0)),"junit",Map.of("tests",1,"failures",1,"errors",0,"skipped",0,"details",List.of(Map.of("className",GeneratedReproduction.SELECTOR,"testName","duplicateChargeOnRetry","type","org.opentest4j.AssertionFailedError","message",GeneratedReproduction.SIGNATURE)))));
    }
    @Test void matchesOnlyExactGeneratedFailureSignature(){assertEquals("EXPECTED_FAILURE_OBSERVED",ReproductionOutcome.evaluate(json.valueToTree(metadata("ASSERTION_FAILED")),"","hash",0,2));}
    @Test void rejectsWrongAssertionAndMissingProvenance(){
        String encoded=json.writeValueAsString(metadata("ASSERTION_FAILED"));
        assertEquals("UNEXPECTED_FAILURE",ReproductionOutcome.evaluate(json.readTree(encoded.replace("<2>","<3>")),"","hash",0,2));
        assertEquals("INFRASTRUCTURE_ERROR",ReproductionOutcome.evaluate(json.valueToTree(metadata("ASSERTION_FAILED")),"","different-hash",0,2));
    }
    @Test void repairsOnlyGeneratedCompilationWithinLimit(){
        var values=metadata("COMPILATION_FAILED");values.put("stages",List.of(Map.of("goals",List.of("test-compile"),"exitCode",1)));
        var meta=json.valueToTree(values);String log=GeneratedReproduction.CLASS+".java compiler error";
        assertEquals("COMPILE_ERROR",ReproductionOutcome.evaluate(meta,log,"hash",0,2));assertEquals("COMPILE_ERROR",ReproductionOutcome.evaluate(meta,log,"hash",1,2));
        assertEquals("REPAIR_EXHAUSTED",ReproductionOutcome.evaluate(meta,log,"hash",2,2));
        assertEquals("INFRASTRUCTURE_ERROR",ReproductionOutcome.evaluate(meta,"Production.java error","hash",0,2));
    }
    @Test void passingTestIsNotAReproduction(){var meta=metadata("PASSED");meta.put("junit",Map.of("tests",1,"skipped",0));assertEquals("NOT_REPRODUCED",ReproductionOutcome.evaluate(json.valueToTree(meta),"","hash",0,2));}
}
