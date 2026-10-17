package com.bugtrace.backend;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class RoleToolPolicyTest {
    @TempDir Path base;
    @Test void evidenceRoleCannotPatchQueueOrCreateTests() {
        for(String tool:List.of("proposePatch","requestExperiment","createTest","requestApprovedChecks","shell","http"))
            assertThrows(IllegalArgumentException.class,()->RoleToolPolicy.authorize(AgentRolePolicy.Role.EVIDENCE_ANALYST,tool));
        for(String tool:List.of("searchCode","readFile","readEvidence"))assertDoesNotThrow(()->RoleToolPolicy.authorize(AgentRolePolicy.Role.EVIDENCE_ANALYST,tool));
    }
    @Test void completeRoleMatrixIsEnforced() {
        for(var role:AgentRolePolicy.Role.values())for(var other:AgentRolePolicy.Role.values())for(String tool:RoleToolPolicy.tools(other)) {
            if(RoleToolPolicy.tools(role).contains(tool))assertDoesNotThrow(()->RoleToolPolicy.authorize(role,tool));
            else assertThrows(IllegalArgumentException.class,()->RoleToolPolicy.authorize(role,tool));
        }
        assertThrows(UnsupportedOperationException.class,()->RoleToolPolicy.tools(AgentRolePolicy.Role.PATCH_AGENT).add("shell"));
    }
    @Test void rejectsUnsafePaths() {
        for(String path:List.of("../secret","/etc/passwd","C:/secret","C:\\secret","src/%2e%2e/secret","src//Test.java","./Test.java","src/../Test.java"))assertFalse(RoleToolPolicy.safePath(path));
        assertTrue(RoleToolPolicy.safePath("src/test/java/demo/RetryTest.java"));
    }
    @Test void draftTestPathsAreScoped() {
        assertTrue(RoleToolPolicy.testPath("src/test/java/demo/RetryTest.java"));
        assertFalse(RoleToolPolicy.testPath("src/main/java/demo/RetryTest.java"));
        assertFalse(RoleToolPolicy.testPath("src/test/java/../../secret.java"));
    }
    @Test void selectedTestCannotBeShellOrUnselectedClass() {
        var selected=Set.of("src/test/java/demo/RetryTest.java");
        assertTrue(RoleToolPolicy.selectedTest("RetryTest",selected));assertTrue(RoleToolPolicy.selectedTest("demo.RetryTest",selected));
        for(String selector:List.of("OtherTest","RetryTest;whoami","RetryTest -DskipTests","../RetryTest","*"))assertFalse(RoleToolPolicy.selectedTest(selector,selected));
    }
    @Test void patchHeadersMustMatchExistingSelectedFiles() {
        String valid="--- a/src/Payment.java\n+++ b/src/Payment.java\n@@ -1 +1 @@\n-old\n+new\n";
        assertDoesNotThrow(()->RoleToolPolicy.patchHeaders(valid,Set.of("src/Payment.java")));
        assertThrows(IllegalArgumentException.class,()->RoleToolPolicy.patchHeaders(valid,Set.of("src/Other.java")));
        assertThrows(IllegalArgumentException.class,()->RoleToolPolicy.patchHeaders(valid.replace("src/Payment.java","../secret"),Set.of("../secret")));
        assertThrows(IllegalArgumentException.class,()->RoleToolPolicy.patchHeaders("execute command",Set.of()));
    }
    @Test void exactArgumentKeysPreventRoleAndPermissionOverrides() {
        var json=JsonMapper.builder().build();
        assertDoesNotThrow(()->RoleToolPolicy.fields(json.readTree("{\"query\":\"charge\"}"),"query"));
        assertThrows(IllegalArgumentException.class,()->RoleToolPolicy.fields(json.readTree("{\"query\":\"charge\",\"role\":\"PATCH_AGENT\"}"),"query"));
        assertThrows(IllegalArgumentException.class,()->RoleToolPolicy.number(json.readTree("{\"timeoutSeconds\":301}"),"timeoutSeconds",1,300));
    }
    @Test void fileReaderIsBoundedAndDoesNotChangeSource() throws Exception {
        Path file=base.resolve("Checkout.java");Files.writeString(file,"first\nsecond\nthird\n");
        assertEquals("second\nthird",RoleToolFiles.read(base,"Checkout.java",2,3));
        assertThrows(IllegalArgumentException.class,()->RoleToolFiles.read(base,"../Checkout.java",1,1));
        assertThrows(IllegalArgumentException.class,()->RoleToolFiles.read(base,"Checkout.java",1,201));
        assertThrows(IllegalArgumentException.class,()->RoleToolFiles.read(base,"Checkout.java",1,4));
        assertEquals("first\nsecond\nthird\n",Files.readString(file));
    }
    @Test void modelToolRequestsRemainDataUntilGatewayAuthorization() {
        String output="""
            {"summary":"MOCK","evidenceIds":[],"missingEvidence":["No actual evidence"],"testDraft":"","patchDraft":"","checks":[],
            "experimentType":"REQUEST_MORE_EVIDENCE","toolRequests":[{"tool":"proposePatch","arguments":{"diff":"untrusted"}}]}
            """;
        var parsed=AgentRoleOutput.parse(output,AgentRolePolicy.Role.EVIDENCE_ANALYST,Set.of());
        assertEquals(1,parsed.toolRequests().size());
        assertThrows(IllegalArgumentException.class,()->RoleToolPolicy.authorize(AgentRolePolicy.Role.EVIDENCE_ANALYST,parsed.toolRequests().getFirst().tool()));
    }
}
