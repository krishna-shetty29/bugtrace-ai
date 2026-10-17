package com.bugtrace.backend;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;

public final class ControlledScenarioReport {
    public static final String PREFIX="BUGTRACE_SCENARIO_REPORT ";
    public record Summary(String scenario,int repetitions,int completed,int duplicates,int passes,int errors,int clientTimeouts,
        List<Integer> chargeCounts,Double duplicateRate,Double wilson95Lower,Double wilson95Upper,String interpretation) {}
    public record Observed(String outcome,Summary summary) {}
    public static Summary parse(String stdout,String scenario,int repetitions) {
        ControlledScenarioSource.parameters(scenario,repetitions);
        var lines=stdout.lines().filter(line->line.startsWith(PREFIX)).toList();
        if(lines.size()!=1)throw new IllegalArgumentException("Exactly one complete scenario report is required");
        String text=lines.getFirst().substring(PREFIX.length());if(text.length()>4096)throw new IllegalArgumentException("Oversized report");
        var json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        var node=json.readTree(text);RoleToolPolicy.fields(node,"scenario","repetitions","completed","duplicates","passes","errors","clientTimeouts","chargeCounts");
        if(!scenario.equals(node.path("scenario").asString())||integer(node,"repetitions")!=repetitions)throw new IllegalArgumentException("Scenario/count mismatch");
        var counts=new ArrayList<Integer>();int errors=0,duplicates=0,passes=0;
        if(!node.path("chargeCounts").isArray())throw new IllegalArgumentException("Missing repetition counts");
        int max=scenario.equals("CONCURRENT_RETRIES")?3:2;
        for(var count:node.get("chargeCounts")) {
            if(!count.isIntegralNumber()||!count.toString().matches("-1|[123]"))throw new IllegalArgumentException("Invalid charge count integer");int value=count.asInt();
            if(value!=-1&&(value<1||value>max))throw new IllegalArgumentException("Invalid trial charge count");
            counts.add(value);if(value==-1)errors++;else if(value>1)duplicates++;else passes++;
        }
        int completed=passes+duplicates,timeouts=integer(node,"clientTimeouts");
        if(counts.size()!=repetitions||integer(node,"errors")!=errors||integer(node,"duplicates")!=duplicates||integer(node,"passes")!=passes||integer(node,"completed")!=completed
            ||timeouts!=(scenario.equals("DELAYED_RESPONSE")?completed:0))throw new IllegalArgumentException("Inconsistent repetition totals");
        Double rate=completed==0?null:(double)duplicates/completed,low=null,high=null;
        if(rate!=null) {
            double z=1.959963984540054,z2=z*z,denom=1+z2/completed;
            double center=(rate+z2/(2*completed))/denom,margin=z*Math.sqrt(rate*(1-rate)/completed+z2/(4.0*completed*completed))/denom;
            low=Math.max(0,center-margin);high=Math.min(1,center+margin);
        }
        String note=errors>0?"INCONCLUSIVE: ordering/setup errors are excluded from the duplicate-rate denominator."
            :duplicates>0?"Duplicate charging was observed in this controlled schedule; rate is descriptive, not a production probability."
            :"No duplicates observed in this batch; passing repetitions do not prove absence of an intermittent bug.";
        if(completed<20)note+=" Fewer than 20 valid repetitions: limited evidence.";
        return new Summary(scenario,repetitions,completed,duplicates,passes,errors,timeouts,List.copyOf(counts),rate,low,high,note);
    }
    private static int integer(JsonNode node,String field){var value=node.get(field);if(value==null||!value.isIntegralNumber()||!value.toString().matches("[0-9]|[12][0-9]|30"))throw new IllegalArgumentException("Invalid report integer");return value.asInt();}
    public static Observed evaluate(JsonNode metadata,String stdout,String hash,String scenario,int repetitions) {
        var generated=metadata.path("generatedTest");boolean compiled=false;
        for(var stage:metadata.path("stages"))for(var goal:stage.path("goals"))if(goal.asString().equals("test-compile")&&stage.path("exitCode").asInt(-1)==0)compiled=true;
        if(!compiled||!hash.equals(generated.path("installedSourceSha256").asString())||!generated.path("unchangedAfterRun").asBoolean(false)||!metadata.path("snapshotVerifiedAfter").asBoolean(false))return new Observed("INFRASTRUCTURE_ERROR",null);
        try {
            var report=parse(stdout,scenario,repetitions);var junit=metadata.path("junit");
            if(junit.path("tests").asInt()!=1||junit.path("skipped").asInt()!=0||junit.path("errors").asInt()!=0)return new Observed("UNEXPECTED_FAILURE",report);
            if(report.errors()>0)return new Observed("UNEXPECTED_FAILURE",report);
            if(report.duplicates()==0)return new Observed(metadata.path("classification").asString().equals("PASSED")&&junit.path("failures").asInt()==0?"NOT_REPRODUCED":"UNEXPECTED_FAILURE",report);
            if(!metadata.path("classification").asString().equals("ASSERTION_FAILED")||junit.path("failures").asInt()!=1)return new Observed("UNEXPECTED_FAILURE",report);
            for(var failure:junit.path("details"))if(failure.path("className").asString().equals(GeneratedReproduction.SELECTOR)
                &&failure.path("testName").asString().equals("duplicateChargeOnRetry")&&failure.path("type").asString().equals("org.opentest4j.AssertionFailedError")
                &&failure.path("message").asString("").contains("BUGTRACE_SCENARIO_DUPLICATES")
                &&failure.path("message").asString("").matches("(?s).*expected:\\s*<0>\\s*but was:\\s*<"+report.duplicates()+">.*"))return new Observed("EXPECTED_FAILURE_OBSERVED",report);
            return new Observed("UNEXPECTED_FAILURE",report);
        }catch(Exception invalidReport){return new Observed("INFRASTRUCTURE_ERROR",null);}
    }
}
