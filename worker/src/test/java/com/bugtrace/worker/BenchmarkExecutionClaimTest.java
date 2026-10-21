package com.bugtrace.worker;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BenchmarkExecutionClaimTest {
    private final BenchmarkWorkerRegistration registration=new BenchmarkWorkerRegistration(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),"case-001",BenchmarkWorkerRegistration.TEST_PATH,BenchmarkWorkerRegistration.TEST_CLASS,Map.of("network","none"));
    private Map<String,Object> binding() {
        var b=new LinkedHashMap<String,Object>();b.put("registrationId",registration.registrationId());b.put("investigationId",registration.investigationId());b.put("snapshotId",registration.snapshotId());b.put("contentHash",registration.contentHash());b.put("caseId",registration.caseId());b.put("collectionVersion","1.0.0");b.put("releaseCommit","6c4f8856bb7e4d49e953a8b086d594d2f505838d");b.put("runId",UUID.randomUUID());b.put("sourceVersion",1);b.put("notAfter",Instant.now().plusSeconds(60).toString());b.put("timeoutSeconds",60);b.put("environment",registration.environment());b.put("sourceSha256",GeneratedTestOverlay.sha("source"));return b;
    }
    private Map<String,Object> claim(Map<String,Object> binding) {
        return new HashMap<>(Map.of("benchmarkBinding",binding,"generatedTest",new GeneratedTestOverlay.Payload(registration.testPath(),registration.testClass(),"source",GeneratedTestOverlay.sha("source"),"failure"),"timeoutSeconds",60,"notAfter",binding.get("notAfter")));
    }
    @Test void acceptsExactRegistrationSourceAndDeadline() {assertDoesNotThrow(()->registration.validateClaim(claim(binding())));}
    @Test void rejectsForeignRegistrationSnapshotSourceAndEnvironment() {
        for(String key:List.of("registrationId","investigationId","snapshotId","contentHash","caseId","sourceSha256","collectionVersion","releaseCommit")){var b=binding();b.put(key,"different");assertThrows(Exception.class,()->registration.validateClaim(claim(b)),key);}
        var b=binding();b.put("environment",Map.of("network","host"));assertThrows(Exception.class,()->registration.validateClaim(claim(b)));
    }
    @Test void rejectsAbsentOrExpiredDeadline() {
        var claim=claim(binding());claim.remove("notAfter");assertThrows(Exception.class,()->registration.validateClaim(claim));
        claim.put("notAfter",Instant.now().minusSeconds(1).toString());assertThrows(Exception.class,()->registration.validateClaim(claim));
    }
    @Test void rejectsUnboundClaimAndInvalidVersion() {
        assertThrows(Exception.class,()->registration.validateClaim(Map.of()));
        var b=binding();b.put("sourceVersion",4);assertThrows(Exception.class,()->registration.validateClaim(claim(b)));
    }
}
