package com.bugtrace.backend;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkExecutionPolicyTest {
    private final JsonMapper json=JsonMapper.builder().build();
    @Test void clampsJobToRegisteredDeadline() {
        Instant now=Instant.parse("2026-10-20T00:00:00Z");
        assertEquals(120,BenchmarkExecutionPolicy.timeout(0,now.plusSeconds(1000),now));
        assertEquals(19,BenchmarkExecutionPolicy.timeout(2,java.sql.Timestamp.from(now.plusSeconds(20)),now));
    }
    @Test void rejectsExhaustedExperimentBudget() {assertThrows(IllegalArgumentException.class,()->BenchmarkExecutionPolicy.timeout(3,Instant.now().plusSeconds(1000),Instant.now()));}
    @Test void rejectsExpiredDeadline() {assertThrows(IllegalArgumentException.class,()->BenchmarkExecutionPolicy.timeout(0,Instant.now(),Instant.now()));}
    private Object junit(String type,String message,int errors,int skipped) {return Map.of("tests",1,"failures",1,"errors",errors,"skipped",skipped,"files",List.of(Map.of("name","TEST-"+BenchmarkExecutionPolicy.CLASS+".xml")),"details",List.of(Map.of("className",BenchmarkExecutionPolicy.CLASS,"type",type,"message",message)));}
    @Test void confirmsActualIntendedAssertionOnly() {
        var result=json.valueToTree(Map.of("classification","ASSERTION_FAILED","junit",junit("org.opentest4j.AssertionFailedError","DUPLICATE expected: <1> but was: <2>",0,0)));
        assertEquals("EXPECTED_FAILURE_OBSERVED",BenchmarkExecutionPolicy.observation(result,"DUPLICATE"));
        assertEquals("EXPECTED_FAILURE_NOT_CONFIRMED",BenchmarkExecutionPolicy.observation(result,"OTHER"));
    }
    @Test void arbitraryExitOrInfrastructureIsNotReproduction() {
        assertEquals("NO_VALID_ASSERTION_OBSERVATION",BenchmarkExecutionPolicy.observation(json.valueToTree(Map.of("classification","TIMED_OUT")),"DUPLICATE"));
    }
    @Test void junitErrorsAndSkipsCannotConfirmReproduction() {
        for(int[] flags:List.of(new int[]{1,0},new int[]{0,1}))assertEquals("NO_VALID_ASSERTION_OBSERVATION",BenchmarkExecutionPolicy.observation(json.valueToTree(Map.of("classification","ASSERTION_FAILED","junit",junit("java.lang.AssertionError","DUPLICATE",flags[0],flags[1]))),"DUPLICATE"));
    }
    @Test void onlyAssertionTypesQualify() {assertEquals("EXPECTED_FAILURE_NOT_CONFIRMED",BenchmarkExecutionPolicy.observation(json.valueToTree(Map.of("classification","ASSERTION_FAILED","junit",junit("java.lang.IllegalStateException","DUPLICATE",0,0))),"DUPLICATE"));}
    private String compiler(String file) {return "[ERROR] COMPILATION ERROR\n[ERROR] /workspace/"+file+":[8,10] cannot find symbol\n[ERROR] maven-compiler-plugin Compilation failure";}
    @Test void generatedCompilerDiagnosticQualifies() {assertDoesNotThrow(()->BenchmarkExecutionPolicy.requireCompilerRepair(json.valueToTree(Map.of("classification","COMPILATION_FAILED")),compiler("GeneratedBenchmarkReproductionTest.java"),"",0));}
    @Test void foreignFileOrMissingDiagnosticDoesNotQualify() {
        for(String log:List.of(compiler("CheckoutProcessor.java"),compiler("GeneratedBenchmarkReproductionTest.java")+"\n"+compiler("Other.java"),"Docker error"))assertThrows(IllegalArgumentException.class,()->BenchmarkExecutionPolicy.requireCompilerRepair(json.valueToTree(Map.of("classification","COMPILATION_FAILED")),log,"",0));
    }
    @Test void assertionAndDependencyFailuresAreNotRepairable() {
        for(String outcome:List.of("ASSERTION_FAILED","TIMED_OUT","INFRASTRUCTURE_ERROR"))assertThrows(IllegalArgumentException.class,()->BenchmarkExecutionPolicy.requireCompilerRepair(json.valueToTree(Map.of("classification",outcome)),compiler("GeneratedBenchmarkReproductionTest.java"),"",0));
    }
    @Test void thirdRepairIsRejected() {var ex=assertThrows(IllegalArgumentException.class,()->BenchmarkExecutionPolicy.requireCompilerRepair(json.valueToTree(Map.of("classification","COMPILATION_FAILED")),compiler("GeneratedBenchmarkReproductionTest.java"),"",2));assertTrue(ex.getMessage().contains("REPAIR_EXHAUSTED"));}
    private BenchmarkReproduction.Spec spec(String assertion) {return new BenchmarkReproduction.Spec("package x; class Test {void check(){"+assertion+";}}","one charge","response loss",List.of("CODE:id"),"DUPLICATE","Proposal");}
    @Test void repairsCannotWeakenAssertions() {assertThrows(IllegalArgumentException.class,()->BenchmarkExecutionPolicy.preserveAssertion(spec("assertEquals(1, count)"),spec("assertEquals(2, count)")));assertDoesNotThrow(()->BenchmarkExecutionPolicy.preserveAssertion(spec("assertEquals(1, count)"),spec("assertEquals(1, count)")));}
    @Test void changedContractIsRejected() {var s=spec("assertEquals(1, count)");var changed=new BenchmarkReproduction.Spec(s.testSource(),"two charges",s.requiredFaultScenario(),s.evidenceIds(),s.expectedFailureSignature(),s.explanation());assertThrows(IllegalArgumentException.class,()->BenchmarkExecutionPolicy.preserveAssertion(s,changed));}
    @Test void rejectsForeignResultBinding() {var binding=json.valueToTree(Map.of("registrationId",UUID.randomUUID(),"investigationId",UUID.randomUUID(),"caseId","case-001","environment",Map.of()));assertThrows(IllegalArgumentException.class,()->BenchmarkExecutionPolicy.validateResult(Map.of("classification","PASSED"),binding));}
    @Test void acceptsFullyVerifiedWorkerResultAndRejectsTamperedSource() {
        UUID registration=UUID.randomUUID(),investigation=UUID.randomUUID();String hash="a".repeat(64);var env=Map.of("network","none");
        var binding=json.valueToTree(Map.of("registrationId",registration,"investigationId",investigation,"caseId","case-001","environment",env,"sourceSha256",hash,"notAfter","2026-10-20T01:00:00Z","timeoutSeconds",60));
        var result=new LinkedHashMap<String,Object>();result.put("classification","ASSERTION_FAILED");result.put("notAfter","2026-10-20T01:00:00Z");result.put("timeoutSeconds",60);result.put("benchmarkRegistrationId",registration);result.put("benchmarkInvestigationId",investigation);result.put("benchmarkCaseId","case-001");result.put("registeredEnvironment",env);result.put("snapshotVerifiedAfter",true);result.put("containerJava21Verified",true);result.put("environmentValidatedContainers",2);
        var overlay=new LinkedHashMap<String,Object>(Map.of("path",BenchmarkExecutionPolicy.PATH,"className",BenchmarkExecutionPolicy.CLASS,"requestedSourceSha256",hash,"installedSourceSha256",hash,"unchangedAfterRun",true));result.put("generatedTest",overlay);
        assertDoesNotThrow(()->BenchmarkExecutionPolicy.validateResult(result,binding));
        overlay.put("installedSourceSha256","b".repeat(64));assertThrows(IllegalArgumentException.class,()->BenchmarkExecutionPolicy.validateResult(result,binding));
        overlay.put("installedSourceSha256",hash);result.put("snapshotVerifiedAfter",false);assertThrows(IllegalArgumentException.class,()->BenchmarkExecutionPolicy.validateResult(result,binding));
    }
    @Test void earlyInfrastructureFailureIsPreservedWithoutClaimingReproduction() {
        var metadata=Map.<String,Object>of("classification","INFRASTRUCTURE_ERROR","evidenceComplete",false,"technicalError","Container did not start");
        assertDoesNotThrow(()->BenchmarkExecutionPolicy.validateResult(metadata,json.valueToTree(Map.of())));
        assertEquals("NO_VALID_ASSERTION_OBSERVATION",BenchmarkExecutionPolicy.observation(json.valueToTree(metadata),"DUPLICATE"));
    }
    @Test void wrongReportCannotConfirmExpectedAssertion() {
        var report=new LinkedHashMap<>((Map<String,Object>)junit("java.lang.AssertionError","DUPLICATE",0,0));report.put("files",List.of(Map.of("name","TEST-Other.xml")));
        assertEquals("NO_VALID_ASSERTION_OBSERVATION",BenchmarkExecutionPolicy.observation(json.valueToTree(Map.of("classification","ASSERTION_FAILED","junit",report)),"DUPLICATE"));
    }
}
