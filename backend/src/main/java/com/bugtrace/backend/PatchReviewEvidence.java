package com.bugtrace.backend;

import java.nio.file.*;
import java.nio.charset.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class PatchReviewEvidence {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public static final List<String> SCENARIOS=List.of("RETRY_AFTER_RESPONSE_LOSS","DELAYED_RESPONSE","CONCURRENT_RETRIES","RETRY_AFTER_COMPLETION");
    private PatchReviewEvidence(){}
    public record Counts(int tests,int failures,int errors,int skipped) {int passed(){return tests-failures-errors-skipped;}}
    private static org.w3c.dom.Document parseXml(Path path)throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var builder=factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){@Override public void error(org.xml.sax.SAXParseException error)throws org.xml.sax.SAXException{throw error;}@Override public void fatalError(org.xml.sax.SAXParseException error)throws org.xml.sax.SAXException{throw error;}});
        return builder.parse(path.toFile());
    }
    public static Counts junit(Path path) throws Exception {
        var root=parseXml(path).getDocumentElement();
        int tests=Integer.parseInt(root.getAttribute("tests")),failures=Integer.parseInt(root.getAttribute("failures")),errors=Integer.parseInt(root.getAttribute("errors")),skipped=Integer.parseInt(root.getAttribute("skipped"));
        if(tests<0||failures<0||errors<0||skipped<0||tests-failures-errors-skipped<0)throw new IllegalArgumentException("Invalid JUnit totals");
        var nodes=root.getElementsByTagName("testcase");int f=0,e=0,s=0;
        for(int i=0;i<nodes.getLength();i++){Element test=(Element)nodes.item(i);f+=test.getElementsByTagName("failure").getLength();e+=test.getElementsByTagName("error").getLength();s+=test.getElementsByTagName("skipped").getLength();}
        if(nodes.getLength()!=tests||f!=failures||e!=errors||s!=skipped)throw new IllegalArgumentException("JUnit cases disagree with totals");
        return new Counts(tests,failures,errors,skipped);
    }
    public static String decodeLog(Path path) throws Exception {
        byte[] bytes=PatchReviewFiles.read(path,8_000_000);
        if(bytes.length>=2&&(bytes[0]&255)==255&&(bytes[1]&255)==254)return new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16LE);
        if(bytes.length>=2&&(bytes[0]&255)==254&&(bytes[1]&255)==255)return new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16BE);
        return new String(bytes,StandardCharsets.UTF_8);
    }
    public static Map<String,Object> validate(JsonNode report,Path run,Path base,Path proposal,Set<String> changed) throws Exception {
        var expected=new HashSet<String>();for(String variant:List.of("original","patched"))for(String suffix:List.of("compile","RETRY_AFTER_RESPONSE_LOSS","DELAYED_RESPONSE","CONCURRENT_RETRIES","RETRY_AFTER_COMPLETION","independent","existing","full-suite"))expected.add(variant+"-"+suffix);
        var cases=new HashMap<String,JsonNode>();boolean targets=true,knownSuite=false;int failureCount=0;
        var baseFiles=new ArrayList<String>();try(var stream=Files.walk(base)){stream.filter(Files::isRegularFile).forEach(path->baseFiles.add(base.relativize(path).toString().replace('\\','/')));}
        for(JsonNode item:report.path("cases")) {
            String name=item.path("name").asString();if(!expected.remove(name)||cases.put(name,item)!=null)throw new IllegalArgumentException("Unexpected or duplicate verification case");
            boolean patched=name.startsWith("patched-");if(item.path("patched").asBoolean()!=patched)throw new IllegalArgumentException("Case variant mismatch");
            Path recordPath=PatchReviewFiles.safe(run,name+"/evidence/execution.json");
            JsonNode execution=JSON.readTree(PatchReviewFiles.text(recordPath,256_000));
            if(!DiagnosisIntegrity.hash(execution).equals(DiagnosisIntegrity.hash(item.path("execution"))))throw new IllegalArgumentException("Execution record mismatch");
            for(String relative:baseFiles) {
                Path actual=PatchReviewFiles.safe(run,name+"/source/"+relative);
                Path intended=PatchReviewFiles.safe(patched&&changed.contains(relative)?proposal:base,relative);
                if(!PatchReviewFiles.sha(actual).equals(PatchReviewFiles.sha(intended)))throw new IllegalArgumentException("Executed source differs from base/proposal");
            }
            int tests=0,failures=0,errors=0,skipped=0;var reports=item.path("reports");
            for(JsonNode suite:reports) {
                Path xml=Path.of(suite.path("record").path("path").asString()).toAbsolutePath().normalize();
                String xmlRelative=run.toAbsolutePath().normalize().relativize(xml).toString().replace('\\','/');
                if(!xmlRelative.startsWith(name+"/evidence/surefire-reports/")||!xml.getFileName().toString().startsWith("TEST-")||!xml.toString().endsWith(".xml"))throw new IllegalArgumentException("Unexpected report path");
                xml=PatchReviewFiles.safe(run,xmlRelative);if(!PatchReviewFiles.sha(xml).equalsIgnoreCase(suite.path("record").path("sha256").asString()))throw new IllegalArgumentException("JUnit file changed");
                Counts count=junit(xml);tests+=count.tests();failures+=count.failures();errors+=count.errors();skipped+=count.skipped();
                JsonNode numbers=suite.path("counts");if(numbers.path("tests").asInt(-1)!=count.tests()||numbers.path("failures").asInt(-1)!=count.failures()||numbers.path("errors").asInt(-1)!=count.errors()||numbers.path("skipped").asInt(-1)!=count.skipped()||numbers.path("passed").asInt(-1)!=count.passed())throw new IllegalArgumentException("Report counts disagree with JUnit");
                var document=parseXml(xml);var nodes=document.getDocumentElement().getElementsByTagName("testcase");var actualTests=new ArrayList<Map<String,Object>>();
                for(int i=0;i<nodes.getLength();i++) {
                    Element test=(Element)nodes.item(i);var failed=test.getElementsByTagName("failure");var errored=test.getElementsByTagName("error");var omitted=test.getElementsByTagName("skipped");
                    String outcome=failed.getLength()>0?"FAILED":errored.getLength()>0?"ERROR":omitted.getLength()>0?"SKIPPED":"PASSED";
                    String message=failed.getLength()>0?((Element)failed.item(0)).getAttribute("message"):errored.getLength()>0?((Element)errored.item(0)).getAttribute("message"):"";
                    actualTests.add(Map.of("class",test.getAttribute("classname"),"name",test.getAttribute("name"),"status",outcome,"message",message));
                }
                if(!DiagnosisIntegrity.hash(actualTests).equals(DiagnosisIntegrity.hash(suite.path("testcases"))))throw new IllegalArgumentException("Displayed tests or assertions disagree with JUnit");
            }
            JsonNode numbers=item.path("junit");
            if(!name.endsWith("-compile")&&(numbers.path("tests").asInt(-1)!=tests||numbers.path("failures").asInt(-1)!=failures||numbers.path("errors").asInt(-1)!=errors||numbers.path("skipped").asInt(-1)!=skipped||numbers.path("passed").asInt(-1)!=tests-failures-errors-skipped))throw new IllegalArgumentException("Aggregate test totals disagree");
            String status=execution.path("timedOut").asBoolean()?"TIMED_OUT":execution.path("oomKilled").asBoolean()?"RESOURCE_LIMIT":execution.path("exitCode").asInt(-1)==0?(name.endsWith("-compile")||(tests>0&&failures==0&&errors==0)?"PASSED":"INCOMPLETE"):(failures>0&&errors==0?"ASSERTION_FAILED":"ERROR_REQUIRES_REVIEW");
            if(!status.equals(item.path("status").asString()))throw new IllegalArgumentException("Displayed outcome differs from execution record");
            boolean clean=execution.path("exitCode").asInt(-1)==0&&!execution.path("timedOut").asBoolean()&&!execution.path("oomKilled").asBoolean();
            if(patched&&!name.endsWith("full-suite"))targets&=clean&&(name.endsWith("compile")||(tests>0&&failures==0&&errors==0&&skipped==0));
            JsonNode scenario=item.path("scenario");
            if(!scenario.isMissingNode()&&!scenario.isNull()) {
                String log=decodeLog(PatchReviewFiles.safe(run,name+"/evidence/stdout.log"));var summaries=log.lines().filter(line->line.startsWith("BUGTRACE_SCENARIO_REPORT ")).toList();
                if(summaries.size()!=1||!DiagnosisIntegrity.hash(JSON.readTree(summaries.getFirst().substring("BUGTRACE_SCENARIO_REPORT ".length()))).equals(DiagnosisIntegrity.hash(scenario)))throw new IllegalArgumentException("Scenario metrics differ from actual output");
                if(!SCENARIOS.contains(scenario.path("scenario").asString())||!name.endsWith(scenario.path("scenario").asString()))throw new IllegalArgumentException("Scenario name mismatch");
                if(patched)targets&=scenario.path("completed").asInt()==20&&scenario.path("passes").asInt()==20&&scenario.path("duplicates").asInt(-1)==0&&scenario.path("errors").asInt(-1)==0;
            } else if(SCENARIOS.stream().anyMatch(name::endsWith))throw new IllegalArgumentException("Scenario summary missing");
            String overlay=execution.path("sourceOverlay").asString("");
            if(!overlay.isBlank()) {
                if(!Set.of("GeneratedCheckoutReproductionTest.java","IndependentPatchVerificationTest.java").contains(overlay))throw new IllegalArgumentException("Unapproved test overlay");
                if(!PatchReviewFiles.sha(PatchReviewFiles.safe(run,name+"/source/src/test/java/com/bugtrace/fixtures/checkout_timeout/"+overlay)).equalsIgnoreCase(execution.path("overlaySha256").asString()))throw new IllegalArgumentException("Executed test source changed");
            }
            if(name.equals("patched-full-suite")) {
                failureCount=failures+errors;
                boolean legacy=false;
                for(JsonNode suite:reports)for(JsonNode test:suite.path("testcases")) {
                    if(test.path("class").asString().equals("com.bugtrace.fixtures.checkout_timeout.DuplicateChargeTest")&&test.path("name").asString().equals("retryAfterLostResponseCreatesTwoCharges")&&test.path("status").asString().equals("FAILED")&&test.path("message").asString().equals("expected: <2> but was: <1>"))legacy=true;
                }
                knownSuite=failures==1&&errors==0&&skipped==0&&legacy;
                if(clean&&failures==0&&errors==0)knownSuite=true;
            }
        }
        if(!expected.isEmpty())throw new IllegalArgumentException("Verification cases missing");
        for(String scenario:SCENARIOS) {
            String a=cases.get("original-"+scenario).path("execution").path("overlaySha256").asString("");
            String b=cases.get("patched-"+scenario).path("execution").path("overlaySha256").asString("");
            if(a.isBlank()||!a.equalsIgnoreCase(b))throw new IllegalArgumentException("Before/after source mismatch");
            var before=cases.get("original-"+scenario);targets&=before.path("scenario").path("duplicates").asInt()==20&&before.path("scenario").path("errors").asInt(-1)==0&&before.path("junit").path("failures").asInt()==1;
        }
        return Map.of("targetedChecksPassed",targets,"fullSuiteFailures",failureCount,"suiteExceptionRecognized",knownSuite,"sourcePairsIdentical",true,"localEvidenceRevalidated",true,"automaticApplication",false);
    }
}
