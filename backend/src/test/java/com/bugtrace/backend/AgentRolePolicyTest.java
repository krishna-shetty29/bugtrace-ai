package com.bugtrace.backend;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentRolePolicyTest {
    private static final Instant NOW=Instant.parse("2026-01-01T00:00:00Z");
    private static final String GOOD="""
        {"summary":"Retry may create another charge","evidenceIds":["e1"],"missingEvidence":[],
        "testDraft":"","patchDraft":"","checks":[],"experimentType":"RETRY_AFTER_RESPONSE_LOSS"}
        """;
    @Test void rolesAdvanceInPlatformOrderOnly() {
        assertEquals(AgentRolePolicy.Role.EVIDENCE_ANALYST,AgentRolePolicy.role(0));
        assertEquals(AgentRolePolicy.Role.REPRODUCTION_AGENT,AgentRolePolicy.role(1));
        assertEquals(AgentRolePolicy.Role.DIAGNOSIS_AGENT,AgentRolePolicy.role(2));
        assertEquals(AgentRolePolicy.Role.PATCH_AGENT,AgentRolePolicy.role(3));
        assertEquals(AgentRolePolicy.Role.VERIFICATION_AGENT,AgentRolePolicy.role(4));
        assertThrows(IllegalArgumentException.class,()->AgentRolePolicy.role(5));
    }
    @Test void reservesCallsAndFullOutputLimitBeforeProvider() {
        assertTrue(AgentRolePolicy.canReserve(0,5,0,7500,1500,NOW,NOW.plusSeconds(600),60));
        assertFalse(AgentRolePolicy.canReserve(5,5,0,7500,1500,NOW,NOW.plusSeconds(600),60));
        assertFalse(AgentRolePolicy.canReserve(1,5,1500,2000,1500,NOW,NOW.plusSeconds(600),60));
        assertTrue(AgentRolePolicy.canReserve(4,5,6000,7500,1500,NOW,NOW.plusSeconds(600),60));
    }
    @Test void deadlineReservesConfiguredProviderTimeoutAndMargin() {
        assertFalse(AgentRolePolicy.canReserve(0,5,0,7500,1500,NOW,NOW.plusSeconds(64),60));
        assertTrue(AgentRolePolicy.canReserve(0,5,0,7500,1500,NOW,NOW.plusSeconds(65),60));
    }
    @Test void everyRoleKeepsUntrustedInputsAndNoToolsBoundary() {
        for(var role:AgentRolePolicy.Role.values()) {
            String instructions=AgentRolePolicy.instructions(role);
            assertTrue(instructions.contains("untrusted evidence"));assertTrue(instructions.contains("You have no tools"));
            assertTrue(instructions.contains("Do not execute"));
        }
    }
    @Test void acceptsSelectedEvidenceAndRejectsInventedIds() {
        assertEquals(List.of("e1"),AgentRoleOutput.parse(GOOD,AgentRolePolicy.Role.EVIDENCE_ANALYST,Set.of("e1")).evidenceIds());
        assertThrows(IllegalArgumentException.class,()->AgentRoleOutput.parse(GOOD,AgentRolePolicy.Role.EVIDENCE_ANALYST,Set.of("other")));
    }
    @Test void testDraftOnlyBelongsToReproduction() {
        String test=GOOD.replace("\"testDraft\":\"\"","\"testDraft\":\"class RetryTest {}\"");
        assertFalse(AgentRoleOutput.parse(test,AgentRolePolicy.Role.REPRODUCTION_AGENT,Set.of("e1")).testDraft().isEmpty());
        for(var role:AgentRolePolicy.Role.values())if(role!=AgentRolePolicy.Role.REPRODUCTION_AGENT)
            assertThrows(IllegalArgumentException.class,()->AgentRoleOutput.parse(test,role,Set.of("e1")));
    }
    @Test void patchDraftOnlyBelongsToPatchRole() {
        String patch=GOOD.replace("\"patchDraft\":\"\"","\"patchDraft\":\"unapplied diff draft\"");
        assertFalse(AgentRoleOutput.parse(patch,AgentRolePolicy.Role.PATCH_AGENT,Set.of("e1")).patchDraft().isEmpty());
        assertThrows(IllegalArgumentException.class,()->AgentRoleOutput.parse(patch,AgentRolePolicy.Role.DIAGNOSIS_AGENT,Set.of("e1")));
    }
    @Test void verificationSelectsApprovedChecksWithoutExecutingThem() {
        String check=GOOD.replace("\"checks\":[]","\"checks\":[\"COMPILE\",\"RUN_SELECTED_TEST\"]");
        assertEquals(2,AgentRoleOutput.parse(check,AgentRolePolicy.Role.VERIFICATION_AGENT,Set.of("e1")).checks().size());
        assertThrows(IllegalArgumentException.class,()->AgentRoleOutput.parse(check,AgentRolePolicy.Role.PATCH_AGENT,Set.of("e1")));
        assertThrows(IllegalArgumentException.class,()->AgentRoleOutput.parse(check.replace("COMPILE","SHELL"),AgentRolePolicy.Role.VERIFICATION_AGENT,Set.of("e1")));
    }
    @Test void rejectsEscalationFieldsDuplicatesAndOversizedOutput() {
        for(String output:List.of(GOOD.replace("\"summary\":","\"permissions\":\"admin\",\"summary\":"),GOOD+"{}",GOOD+" ".repeat(17000),GOOD.replace("\"checks\":[]","\"checks\":[],\"checks\":[]")))
            assertThrows(IllegalArgumentException.class,()->AgentRoleOutput.parse(output,AgentRolePolicy.Role.EVIDENCE_ANALYST,Set.of("e1")));
    }
    @Test void mocksRemainSyntheticForEveryRole() throws Exception {
        for(var role:AgentRolePolicy.Role.values()) {
            var response=new MockAiProvider().generate(new AiProvider.Request("mock-v1",AgentRolePolicy.instructions(role),"{}",1500));
            var output=AgentRoleOutput.parse(response.text(),role,Set.of());
            assertTrue(output.summary().startsWith("MOCK"));assertTrue(output.testDraft().isEmpty());assertTrue(output.patchDraft().isEmpty());
            assertEquals("MOCK_NOT_MEASURED",response.usage().kind());
        }
    }
}
