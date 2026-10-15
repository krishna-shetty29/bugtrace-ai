package com.bugtrace.worker;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.bugtrace.worker.ExecutionEvidence.*;

class ExecutionEvidenceTest {
    @TempDir Path temp;
    private Reports failed() { return new Reports(1, 1, 0, 0,
        List.of(new Failure("com.example.CheckoutIdempotencyTest", "retry", "org.opentest4j.AssertionFailedError", "expected: <1> but was: <2>")),
        List.of(new ReportFile("TEST-com.example.CheckoutIdempotencyTest.xml", "a".repeat(64), 100))); }

    @Test void recognizesSelectedAssertionButNotUnrelatedFailure() {
        assertEquals(Outcome.ASSERTION_FAILED, classify("FAILED", false, 1, false, "CheckoutIdempotencyTest", "", "", failed()));
        assertEquals(Outcome.INFRASTRUCTURE_ERROR, classify("FAILED", false, 1, false, "OtherTest", "", "", failed()));
    }
    @Test void compilationFailureIsNotBugReproduction() {
        assertEquals(Outcome.COMPILATION_FAILED, classify("FAILED", false, 1, false, "CheckoutIdempotencyTest",
            "[ERROR] COMPILATION ERROR\nmaven-compiler-plugin:compile Compilation failure", "", empty()));
        assertEquals(Outcome.INFRASTRUCTURE_ERROR, classify("FAILED", false, 1, false, null,
            "Could not resolve maven-compiler-plugin", "", empty()));
    }
    @Test void resourceClassificationRequiresOomEvidence() {
        assertEquals(Outcome.RESOURCE_LIMIT, classify("FAILED", true, 137, false, null, "", "", empty()));
        assertEquals(Outcome.INFRASTRUCTURE_ERROR, classify("FAILED", false, 137, false, null, "", "", empty()));
    }
    @Test void checksPassingReportsAndInfrastructureErrors() {
        var passed = new Reports(1, 0, 0, 0, List.of(), failed().files());
        assertEquals(Outcome.PASSED, classify("SUCCEEDED", false, 0, false, "CheckoutIdempotencyTest", "", "", passed));
        assertEquals(Outcome.PASSED, classify("SUCCEEDED", false, 0, true, null, "", "", empty()));
        assertEquals(Outcome.INFRASTRUCTURE_ERROR, classify("SUCCEEDED", false, 0, false, null, "", "", empty()));
        var error = new Reports(1, 0, 1, 0, List.of(), failed().files());
        assertEquals(Outcome.INFRASTRUCTURE_ERROR, classify("FAILED", false, 1, false, null, "", "", error));
    }
    @Test void prioritizesCancellationTimeoutAndWorkerError() {
        assertEquals(Outcome.CANCELLED, classify("CANCELLED", false, null, false, null, "", "", empty()));
        assertEquals(Outcome.TIMED_OUT, classify("TIMED_OUT", false, null, false, null, "", "", empty()));
        assertEquals(Outcome.INFRASTRUCTURE_ERROR, classify("ERROR", false, null, false, null, "", "", empty()));
    }
    @Test void copiesParsesHashesAndPersistsMetadata() throws Exception {
        Path experiment = Files.createDirectory(temp.resolve("experiment"));
        Path source = Files.createDirectories(experiment.resolve("target/surefire-reports"));
        Files.writeString(source.resolve("TEST-com.example.CheckoutIdempotencyTest.xml"), """
            <testsuite tests="1" failures="1" errors="0" skipped="0">
              <testcase classname="com.example.CheckoutIdempotencyTest" name="retry">
                <failure type="org.opentest4j.AssertionFailedError" message="expected: &lt;1&gt; but was: &lt;2&gt;"/>
              </testcase>
            </testsuite>
            """);
        Path evidence = Files.createDirectory(temp.resolve("evidence"));
        Reports reports = collect(experiment, evidence);
        assertEquals(1, reports.tests());
        assertEquals(1, reports.failures());
        assertEquals("expected: <1> but was: <2>", reports.details().getFirst().message());
        assertEquals(64, reports.files().getFirst().sha256().length());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("exitCode", null);
        metadata.put("junit", reports);
        write(evidence, metadata);
        assertTrue(Files.readString(evidence.resolve("execution.json")).contains("CheckoutIdempotencyTest"));
    }
    @Test void rejectsExternalEntityReports() throws Exception {
        Path experiment = Files.createDirectory(temp.resolve("experiment"));
        Path source = Files.createDirectories(experiment.resolve("target/surefire-reports"));
        Files.writeString(source.resolve("TEST-danger.xml"), "<!DOCTYPE testsuite [<!ENTITY x SYSTEM 'file:///secret'>]><testsuite>&x;</testsuite>");
        Path evidence = Files.createDirectory(temp.resolve("evidence"));
        assertThrows(Exception.class, () -> collect(experiment, evidence));
    }
}
