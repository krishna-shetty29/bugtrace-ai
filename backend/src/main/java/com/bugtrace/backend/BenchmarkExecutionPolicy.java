package com.bugtrace.backend;

import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.MethodCallExpr;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed execution contract; model output cannot choose commands, paths or resource limits. */
public final class BenchmarkExecutionPolicy {
    private BenchmarkExecutionPolicy() {}
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public static final String CLASS="com.bugtrace.benchmark.GeneratedBenchmarkReproductionTest";
    public static final String PATH="src/test/java/com/bugtrace/benchmark/GeneratedBenchmarkReproductionTest.java";
    public static int timeout(int jobs,Object deadline,Instant now) {
        if(jobs>=3)throw new IllegalArgumentException("Experiment budget exhausted: three jobs including repaired attempts");
        long remaining=Duration.between(now,BenchmarkReproductionService.deadlineInstant(deadline)).getSeconds()-1;
        if(remaining<1)throw new IllegalArgumentException("Investigation deadline exhausted; no job queued");
        return (int)Math.min(120,remaining);
    }
    public static void validateResult(Map<String,Object> metadata,JsonNode binding) {
        var m=JSON.valueToTree(metadata);
        if("INFRASTRUCTURE_ERROR".equals(m.path("classification").asText())&&!m.path("evidenceComplete").asBoolean(true))return;
        for(String key:List.of("benchmarkRegistrationId","benchmarkInvestigationId","benchmarkCaseId")) {
            String trusted=switch(key){case "benchmarkRegistrationId"->"registrationId";case "benchmarkInvestigationId"->"investigationId";default->"caseId";};
            if(!m.path(key).asText().equals(binding.path(trusted).asText()))throw new IllegalArgumentException("Benchmark result identity differs: "+key);
        }
        if(!DiagnosisIntegrity.hash(m.path("registeredEnvironment")).equals(DiagnosisIntegrity.hash(binding.path("environment"))))throw new IllegalArgumentException("Benchmark environment differs from registration");
        // Infrastructure failures can occur before installation/probe; never promote them to test evidence.
        String classification=m.path("classification").asText();
        if(Set.of("PASSED","ASSERTION_FAILED","COMPILATION_FAILED").contains(classification)) {
            if(!sameInstant(binding.path("notAfter").asText(),m.path("notAfter").asText())||binding.path("timeoutSeconds").asInt()!=m.path("timeoutSeconds").asInt())throw new IllegalArgumentException("Worker result deadline differs from reserved experiment");
            var overlay=m.path("generatedTest");
            if(!PATH.equals(overlay.path("path").asText())||!CLASS.equals(overlay.path("className").asText())
                ||!binding.path("sourceSha256").asText().equals(overlay.path("requestedSourceSha256").asText())
                ||!binding.path("sourceSha256").asText().equals(overlay.path("installedSourceSha256").asText())
                ||!overlay.path("unchangedAfterRun").asBoolean()||!m.path("snapshotVerifiedAfter").asBoolean()
                ||!m.path("containerJava21Verified").asBoolean()||m.path("environmentValidatedContainers").asInt()<1)
                throw new IllegalArgumentException("Benchmark source, snapshot or container verification missing");
        }
    }
    private static boolean sameInstant(String a,String b) {try{return Instant.parse(a).equals(Instant.parse(b));}catch(RuntimeException invalid){return false;}}
    public static String observation(JsonNode result,String expected) {
        if(result==null||result.isNull())return "NOT_COMPLETED";
        var junit=result.path("junit");
        boolean report=false;for(var file:junit.path("files"))if(("TEST-"+CLASS+".xml").equals(file.path("name").asText()))report=true;
        if(!report||junit.path("tests").asInt()<1||junit.path("errors").asInt()!=0||junit.path("skipped").asInt()!=0)return "NO_VALID_ASSERTION_OBSERVATION";
        if("ASSERTION_FAILED".equals(result.path("classification").asText())&&expected!=null&&!expected.isBlank())
            for(var detail:junit.path("details"))if(CLASS.equals(detail.path("className").asText())
                &&Set.of("org.opentest4j.AssertionFailedError","java.lang.AssertionError","org.junit.ComparisonFailure").contains(detail.path("type").asText())
                &&detail.path("message").asText().contains(expected))return "EXPECTED_FAILURE_OBSERVED";
        return "PASSED".equals(result.path("classification").asText())?"TEST_PASSED":"EXPECTED_FAILURE_NOT_CONFIRMED";
    }
    public static void requireCompilerRepair(JsonNode result,String stdout,String stderr,int repairs) {
        if(repairs>=2)throw new IllegalArgumentException("REPAIR_EXHAUSTED: two compiler repairs have been reserved");
        if(result==null||!"COMPILATION_FAILED".equals(result.path("classification").asText()))throw new IllegalArgumentException("Only generated-file compilation errors qualify for repair");
        String log=stdout+"\n"+stderr;
        var matcher=Pattern.compile("(?m)^.*\\[ERROR\\].*?([^\\s/\\\\]+\\.java):\\[\\d+,\\d+\\].*$").matcher(log);
        boolean found=false;
        while(matcher.find()) {
            found=true;
            if(!"GeneratedBenchmarkReproductionTest.java".equals(matcher.group(1)))throw new IllegalArgumentException("Compiler diagnostics include another Java file; repair rejected");
        }
        if(!found||!log.contains("COMPILATION ERROR")||!log.contains("maven-compiler-plugin"))throw new IllegalArgumentException("No generated-file compiler diagnostic found");
    }
    public static void preserveAssertion(BenchmarkReproduction.Spec before,BenchmarkReproduction.Spec after) {
        if(!before.intendedAssertion().equals(after.intendedAssertion())||!before.requiredFaultScenario().equals(after.requiredFaultScenario())
            ||!before.evidenceIds().equals(after.evidenceIds())||!before.expectedFailureSignature().equals(after.expectedFailureSignature()))throw new IllegalArgumentException("Repair changed the reproduction contract");
        if(!assertions(before.testSource()).equals(assertions(after.testSource())))throw new IllegalArgumentException("Repair changed assertion expressions");
    }
    private static List<String> assertions(String source) {
        return StaticJavaParser.parse(source).findAll(MethodCallExpr.class).stream().filter(c->c.getNameAsString().startsWith("assert")).map(Object::toString).toList();
    }
}
