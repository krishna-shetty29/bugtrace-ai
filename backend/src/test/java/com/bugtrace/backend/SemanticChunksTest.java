package com.bugtrace.backend;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SemanticChunksTest {
    @TempDir Path base;
    @Test void preservesMethodAndDocumentProvenanceWithoutChangingSource() throws Exception {
        Files.createDirectories(base.resolve("src/test/java"));
        String source="package demo;\nclass CheckoutTest {\n int charge() {\n  return 42;\n }\n}\n";
        Files.writeString(base.resolve("src/test/java/CheckoutTest.java"),source);
        Files.writeString(base.resolve("README.md"),"# Payments\nCharge once\n## Retry\nReturn original payment\n");
        String hash=GitSnapshot.contentHash(base);
        var corpus=new SemanticChunks().read(base);
        var method=corpus.chunks().stream().filter(c->c.kind().equals("METHOD")).findFirst().orElseThrow();
        assertEquals(3,method.startLine());assertEquals(5,method.endLine());assertTrue(method.isTest());assertTrue(method.text().contains("return 42"));
        assertEquals(2,corpus.chunks().stream().filter(c->c.kind().equals("DOCUMENT_SECTION")).count());
        assertTrue(corpus.diagnostics().isEmpty());assertEquals(hash,GitSnapshot.contentHash(base));
        assertEquals(corpus,new SemanticChunks().read(base));
    }
    @Test void recordsParserFailureAndKeepsItsText() throws Exception {
        Files.writeString(base.resolve("Broken.java"),"class Broken { void pay( {\n");
        var corpus=new SemanticChunks().read(base);
        assertFalse(corpus.diagnostics().isEmpty());assertTrue(corpus.chunks().getFirst().text().contains("pay"));
    }
    @Test void splitsLargeRangesWithoutLosingLines() throws Exception {
        String[] lines=new String[80];Arrays.fill(lines,"some checkout documentation ".repeat(4));
        var chunks=new ArrayList<SemanticChunks.Chunk>();SemanticChunks.add(chunks,"README.md",lines,1,80,"DOCUMENT_SECTION","README");
        assertTrue(chunks.size()>1);assertEquals(1,chunks.getFirst().startLine());assertEquals(80,chunks.getLast().endLine());
        assertEquals(String.join("\n",lines)+"\n",chunks.stream().map(SemanticChunks.Chunk::text).reduce("",String::concat));
        for(int n=1;n<chunks.size();n++)assertEquals(chunks.get(n-1).endLine()+1,chunks.get(n).startLine());
    }
    @Test void rejectsOverlongLinesExplicitly() throws Exception {
        Files.writeString(base.resolve("README.md"),"x".repeat(1300));
        assertTrue(assertThrows(IllegalArgumentException.class,()->new SemanticChunks().read(base)).getMessage().contains("line limit"));
    }
}
