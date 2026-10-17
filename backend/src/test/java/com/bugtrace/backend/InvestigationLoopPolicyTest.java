package com.bugtrace.backend;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.json.JsonMapper;

class InvestigationLoopPolicyTest {
    final JsonMapper json=JsonMapper.builder().build();
    final Instant now=Instant.parse("2026-10-17T00:00:00Z");
    InvestigationLoopPolicy.Limits limits(){return new InvestigationLoopPolicy.Limits(2,3,8,12000,900,300,1800,2);}
    String decision(String type,String decision,List<String> ids){return json.writeValueAsString(new InvestigationLoopPolicy.Decision("hypothesis",ids,type,decision,"reason",List.of()));}
    @Test void acceptsOnlySupportedPlanAndSelectedEvidence(){assertEquals("CONTINUE",InvestigationLoopPolicy.parse(decision("RETRY_AFTER_RESPONSE_LOSS","CONTINUE",List.of("id")),Set.of("id")).decision());}
    @Test void rejectsUnknownEvidence(){assertThrows(IllegalArgumentException.class,()->InvestigationLoopPolicy.parse(decision("RETRY_AFTER_RESPONSE_LOSS","CONTINUE",List.of("invented")),Set.of("id")));}
    @Test void rejectsUnsupportedExperiment(){assertThrows(IllegalArgumentException.class,()->InvestigationLoopPolicy.parse(decision("SHELL_COMMAND","CONTINUE",List.of("id")),Set.of("id")));}
    @Test void rejectsExtraFields(){assertThrows(IllegalArgumentException.class,()->InvestigationLoopPolicy.parse(decision("RETRY_AFTER_RESPONSE_LOSS","CONTINUE",List.of("id")).replaceFirst("\\{","{\"command\":\"run me\","),Set.of("id")));}
    @Test void rejectsDuplicateKeysAndOversizedOutput(){assertThrows(Exception.class,()->InvestigationLoopPolicy.parse("{\"hypothesis\":\"a\",\"hypothesis\":\"b\"}",Set.of("id")));assertThrows(IllegalArgumentException.class,()->InvestigationLoopPolicy.parse("x".repeat(16385),Set.of("id")));}
    @Test void validatesSavedLimits(){assertDoesNotThrow(()->InvestigationLoopPolicy.validate(limits(),1500));assertThrows(IllegalArgumentException.class,()->InvestigationLoopPolicy.validate(new InvestigationLoopPolicy.Limits(4,3,8,12000,900,300,1800,2),1500));}
    @Test void boundsCallsBeforeSending(){assertFalse(InvestigationLoopPolicy.modelFits(8,8,0,12000,1500,now,now.plusSeconds(300),60));}
    @Test void boundsOutputReservationBeforeSending(){assertFalse(InvestigationLoopPolicy.modelFits(0,8,11000,12000,1500,now,now.plusSeconds(300),60));}
    @Test void requiresModelTimeoutToFitWallDeadline(){assertFalse(InvestigationLoopPolicy.modelFits(0,8,0,12000,1500,now,now.plusSeconds(64),60));assertTrue(InvestigationLoopPolicy.modelFits(0,8,0,12000,1500,now,now.plusSeconds(65),60));}
    @Test void executionAllowanceCombinesAllLimits(){assertEquals(50,InvestigationLoopPolicy.executionAllowance(1,3,850,900,300,now,now.plusSeconds(100)));assertEquals(0,InvestigationLoopPolicy.executionAllowance(3,3,0,900,300,now,now.plusSeconds(100)));assertEquals(5,InvestigationLoopPolicy.executionAllowance(0,3,0,900,300,now,now.plusSeconds(10)));}
    @Test void modelCannotContinueAfterExpectedFailure(){assertEquals("FINISHED_REPRODUCED",InvestigationLoopPolicy.afterReview("EXPECTED_FAILURE_OBSERVED","CONTINUE",true));}
    @Test void genericFailureIsNeverReproduction(){assertEquals("FAILED",InvestigationLoopPolicy.afterReview("INFRASTRUCTURE_ERROR","FINISH",true));}
    @Test void continuedPassingExperimentNeedsRemainingBudget(){assertEquals("READY_HYPOTHESIS",InvestigationLoopPolicy.afterReview("NOT_REPRODUCED","CONTINUE",true));assertEquals("BUDGET_EXHAUSTED",InvestigationLoopPolicy.afterReview("NOT_REPRODUCED","CONTINUE",false));}
    @Test void distinguishesNotReproducedAndMissingEvidence(){assertEquals("FINISHED_NOT_REPRODUCED",InvestigationLoopPolicy.afterReview("NOT_REPRODUCED","FINISH",true));assertEquals("NEEDS_EVIDENCE",InvestigationLoopPolicy.afterReview("NOT_REPRODUCED","REQUEST_MORE_EVIDENCE",true));}
    @Test void acceptsBothJdbcTimestampRepresentations(){assertEquals(now,InvestigationLoopPolicy.instant(java.sql.Timestamp.from(now)));assertEquals(now,InvestigationLoopPolicy.instant(now.atOffset(ZoneOffset.UTC)));}
}
