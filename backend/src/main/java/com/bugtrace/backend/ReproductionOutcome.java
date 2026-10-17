package com.bugtrace.backend;

import tools.jackson.databind.JsonNode;

public final class ReproductionOutcome {
    public static String evaluate(JsonNode metadata,String logs,String sourceHash,int attempt,int maxRepairs) {
        var overlay=metadata.path("generatedTest");
        if(!sourceHash.equals(overlay.path("installedSourceSha256").asString()) || !overlay.path("unchangedAfterRun").asBoolean(false)
            || !metadata.path("snapshotVerifiedAfter").asBoolean(false))return "INFRASTRUCTURE_ERROR";
        String classification=metadata.path("classification").asString();
        boolean compiled=false,compileFailed=false;
        for(var stage:metadata.path("stages"))for(var goal:stage.path("goals"))if(goal.asString().equals("test-compile")) {
            compiled|=stage.path("exitCode").asInt(-1)==0;compileFailed|=stage.path("exitCode").asInt(-1)>0;
        }
        if(classification.equals("COMPILATION_FAILED")) {
            if(!compileFailed || !logs.contains(GeneratedReproduction.CLASS+".java"))return "INFRASTRUCTURE_ERROR";
            return attempt>=maxRepairs?"REPAIR_EXHAUSTED":"COMPILE_ERROR";
        }
        if(!compiled)return "INFRASTRUCTURE_ERROR";
        var junit=metadata.path("junit");
        if(classification.equals("PASSED"))return junit.path("tests").asInt()==1 && junit.path("skipped").asInt()==0?"NOT_REPRODUCED":"UNEXPECTED_FAILURE";
        if(!classification.equals("ASSERTION_FAILED"))return "INFRASTRUCTURE_ERROR";
        if(junit.path("tests").asInt()!=1 || junit.path("failures").asInt()!=1 || junit.path("errors").asInt()!=0 || junit.path("skipped").asInt()!=0)return "UNEXPECTED_FAILURE";
        for(var failure:junit.path("details")) {
            String message=failure.path("message").asString("");
            if(failure.path("className").asString().equals(GeneratedReproduction.SELECTOR)
                && failure.path("testName").asString().equals("duplicateChargeOnRetry")
                && failure.path("type").asString().equals("org.opentest4j.AssertionFailedError")
                && message.contains("BUGTRACE_DUPLICATE_CHARGE") && message.matches("(?s).*expected:\\s*<1>\\s*but was:\\s*<2>.*"))return "EXPECTED_FAILURE_OBSERVED";
        }
        return "UNEXPECTED_FAILURE";
    }
}
