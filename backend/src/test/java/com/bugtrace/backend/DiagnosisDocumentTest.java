package com.bugtrace.backend;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosisDocumentTest {
    final JsonMapper json=JsonMapper.builder().build();final UUID job=UUID.randomUUID();
    final AiEvidenceService.Evidence source=new AiEvidenceService.Evidence("CODE:one",new AiEvidenceService.Ref("CODE",UUID.randomUUID(),"chunk",null),"CURRENT_SNAPSHOT_CODE","src/main/java/Checkout.java","a".repeat(40),"SOURCE_LINE",1,3,"hash","class Checkout {}\n");
    DiagnosisDocument.Document document(){return new DiagnosisDocument.Document("Proposed cause remains advisory","One charge","Two charges observed",List.of("Retry same ID"),List.of(new DiagnosisDocument.CodeLocation(source.id(),source.sourceRef(),source.revision(),1,1,"class Checkout {}","Inspect recording path")),List.of(new DiagnosisDocument.ExecutionLink(job,"Actual test failure")),List.of("Caller duplication remains a possible cause to isolate"),List.of("Production provider and restart persistence unverified"));}
    DiagnosisDocument.Document parse(String text){return DiagnosisDocument.parse(text,Map.of(source.id(),source),Set.of(job));}
    @Test void requiresAllSectionsAndKnownLinks(){assertEquals(document(),parse(json.writeValueAsString(document())));}
    @Test void rejectsConfidenceAndPermissionFields(){String valid=json.writeValueAsString(document());for(String extra:List.of("\"confidence\":99,","\"changesAuthorized\":true,","\"toolRequests\":[],"))assertThrows(Exception.class,()->parse(valid.replaceFirst("\\{","{"+extra)));}
    @Test void rejectsMissingSections(){var node=(tools.jackson.databind.node.ObjectNode)json.valueToTree(document());node.remove("unverifiedBehavior");assertThrows(Exception.class,()->parse(node.toString()));}
    @Test void rejectsEmptyAlternatives(){var node=(tools.jackson.databind.node.ObjectNode)json.valueToTree(document());node.putArray("alternativeExplanations");assertThrows(Exception.class,()->parse(node.toString()));}
    @Test void rejectsInventedSourceId(){assertThrows(Exception.class,()->parse(json.writeValueAsString(document()).replace("CODE:one","CODE:invented")));}
    @Test void rejectsExternalPathAndWrongRevision(){String text=json.writeValueAsString(document());assertThrows(Exception.class,()->parse(text.replace(source.sourceRef(),"https://attacker.invalid/source")));assertThrows(Exception.class,()->parse(text.replace(source.revision(),"b".repeat(40))));}
    @Test void rejectsLinesOutsideSelectedChunk(){var node=json.valueToTree(document());((tools.jackson.databind.node.ObjectNode)node.get("relevantCodeLocations").get(0)).put("endLine",4);assertThrows(Exception.class,()->parse(node.toString()));}
    @Test void rejectsCoercedOrOverflowingLineNumbers(){var node=json.valueToTree(document());var code=(tools.jackson.databind.node.ObjectNode)node.get("relevantCodeLocations").get(0);code.put("startLine","1");assertThrows(Exception.class,()->parse(node.toString()));code.put("startLine",4294967297L);assertThrows(Exception.class,()->parse(node.toString()));}
    @Test void rejectsUnknownExecutionAndOmittedExecutions(){assertThrows(Exception.class,()->parse(json.writeValueAsString(document()).replace(job.toString(),UUID.randomUUID().toString())));var node=(tools.jackson.databind.node.ObjectNode)json.valueToTree(document());node.putArray("supportingExecutions");assertThrows(Exception.class,()->parse(node.toString()));}
    @Test void rejectsOversizedAndDuplicateKeyOutput(){assertThrows(Exception.class,()->parse("x".repeat(16385)));assertThrows(Exception.class,()->parse(json.writeValueAsString(document()).replaceFirst("\\{","{\"diagnosis\":\"other\",")));}
    @Test void rejectsHistoricalEvidenceAsCurrentCode(){var historical=new AiEvidenceService.Evidence(source.id(),source.reference(),"HISTORICAL_DIFF",source.sourceRef(),source.revision(),"DIFF_LINE",1,3,"hash",source.text());assertThrows(Exception.class,()->DiagnosisDocument.parse(json.writeValueAsString(document()),Map.of(source.id(),historical),Set.of(job)));}
}
