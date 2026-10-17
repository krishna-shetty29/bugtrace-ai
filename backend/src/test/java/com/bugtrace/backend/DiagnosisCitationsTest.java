package com.bugtrace.backend;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosisCitationsTest {
    @TempDir Path base;
    AiEvidenceService.Evidence source() throws Exception {
        Files.writeString(base.resolve("Checkout.java"),"class Checkout {\n  void charge() {}\n}\n");String text="  void charge() {}\n";
        return new AiEvidenceService.Evidence("CODE:charge",new AiEvidenceService.Ref("CODE",UUID.randomUUID(),"method",null),"CURRENT_SNAPSHOT_CODE","Checkout.java","a".repeat(40),"SOURCE_LINE",2,2,ContextDocuments.hash(text),text);
    }
    DiagnosisDocument.CodeLocation cite(AiEvidenceService.Evidence source,String quote){return new DiagnosisDocument.CodeLocation(source.id(),source.sourceRef(),source.revision(),2,2,quote,"Method in captured source");}
    @Test void validatesExactSnapshotLinesAndHashes() throws Exception {var source=source();var result=DiagnosisCitations.verify(base,cite(source,"  void charge() {}"),source);assertEquals("SOURCE_LINE",result.get("lineKind"));assertEquals(ContextDocuments.hash("  void charge() {}"),result.get("quoteHash"));}
    @Test void rejectsAParaphrasedOrReindentedQuote() throws Exception {var source=source();assertThrows(Exception.class,()->DiagnosisCitations.verify(base,cite(source,"void charge() {}"),source));}
    @Test void detectsConsistentButPoisonedIndexText() throws Exception {var source=source();String forged="  void pay() {}\n";var poisoned=new AiEvidenceService.Evidence(source.id(),source.reference(),source.origin(),source.sourceRef(),source.revision(),source.lineKind(),2,2,ContextDocuments.hash(forged),forged);assertThrows(Exception.class,()->DiagnosisCitations.verifyEvidence(base,poisoned));}
    @Test void rejectsPathEscapeAndOutOfBoundsRange() throws Exception {source();assertThrows(Exception.class,()->DiagnosisCitations.range(base,"../outside.java",1,1));assertThrows(Exception.class,()->DiagnosisCitations.range(base,"Checkout.java",1,99));}
    @Test void detectsChangedCapturedSource() throws Exception {var source=source();Files.writeString(base.resolve("Checkout.java"),"class Checkout {}\n");assertThrows(Exception.class,()->DiagnosisCitations.verifyEvidence(base,source));}
    @Test void protectsChunkBoundaryEvenOutsideDocumentParser() throws Exception {var source=source();var outside=new DiagnosisDocument.CodeLocation(source.id(),source.sourceRef(),source.revision(),1,1,"class Checkout {","outside selected chunk");assertThrows(Exception.class,()->DiagnosisCitations.verify(base,outside,source));}
}
