package com.bugtrace.backend;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PatchReviewFilesTest {
    @TempDir Path temp;
    private final String file=PatchReviewFiles.PREFIX+"PaymentLedger.java";
    private String diff(String path,String old,String replacement){return "diff --git a/"+path+" b/"+path+"\n--- a/"+path+"\n+++ b/"+path+"\n@@ -1 +1 @@\n-"+old+"\n+"+replacement+"\n";}
    @Test void acceptsExactAllowedModification(){assertEquals(Set.of(file),PatchReviewFiles.validateDiff(diff(file,"old","new"),Map.of(file,"old\n"),Map.of(file,"new\n")));}
    @Test void acceptsGitIndexHeader(){String patch=diff(file,"old","new").replace("\n--- a/","\nindex abc1234..def1234 100644\n--- a/");assertEquals(Set.of(file),PatchReviewFiles.validateDiff(patch,Map.of(file,"old\n"),Map.of(file,"new\n")));}
    @Test void acceptsMissingFinalNewlineOnBase(){String patch=diff(file,"old","new").replace("-old\n","-old\n\\ No newline at end of file\n");assertEquals(Set.of(file),PatchReviewFiles.validateDiff(patch,Map.of(file,"old"),Map.of(file,"new\n")));}
    @Test void rejectsForgedNewlineMarker(){String patch=diff(file,"old","new").replace("-old\n","-old\n\\ No newline at end of file\n");assertThrows(IllegalArgumentException.class,()->PatchReviewFiles.validateDiff(patch,Map.of(file,"old\n"),Map.of(file,"new\n")));}
    @Test void rejectsUnrelatedPath(){String path="pom.xml";assertThrows(IllegalArgumentException.class,()->PatchReviewFiles.validateDiff(diff(path,"old","new"),Map.of(path,"old\n"),Map.of(path,"new\n")));}
    @Test void rejectsWrongBase(){assertThrows(IllegalArgumentException.class,()->PatchReviewFiles.validateDiff(diff(file,"wrong","new"),Map.of(file,"old\n"),Map.of(file,"new\n")));}
    @Test void rejectsDiffProposalMismatch(){assertThrows(IllegalArgumentException.class,()->PatchReviewFiles.validateDiff(diff(file,"old","new"),Map.of(file,"old\n"),Map.of(file,"different\n")));}
    @Test void rejectsRenames(){assertThrows(IllegalArgumentException.class,()->PatchReviewFiles.validateDiff("diff --git a/"+file+" b/elsewhere.java\n",Map.of(),Map.of()));}
    @Test void rejectsDuplicateFiles(){String patch=diff(file,"old","new");assertThrows(IllegalArgumentException.class,()->PatchReviewFiles.validateDiff(patch+patch,Map.of(file,"old\n"),Map.of(file,"new\n")));}
    @Test void rejectsTraversal()throws Exception {Files.writeString(temp.resolve("good"),"ok");assertThrows(IllegalArgumentException.class,()->PatchReviewFiles.safe(temp,"../secret"));assertThrows(IllegalArgumentException.class,()->PatchReviewFiles.safe(temp,"C:/secret"));assertEquals("ok",PatchReviewFiles.text(PatchReviewFiles.safe(temp,"good"),10));}
    @Test void rejectsOversize()throws Exception {Path file=Files.writeString(temp.resolve("big"),"12345");assertThrows(IllegalArgumentException.class,()->PatchReviewFiles.read(file,4));}
    @Test void hashesExactBytes()throws Exception {assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",PatchReviewFiles.sha("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
}
