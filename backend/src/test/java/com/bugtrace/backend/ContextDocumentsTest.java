package com.bugtrace.backend;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ContextDocumentsTest {
    @TempDir Path base;
    @Test void retainsExactRevisionLineRangesAndContentHashes() throws Exception {
        String body="# Checkout\nCharge once\n## Retries\nRetain payment ID\n";Files.writeString(base.resolve("README.md"),body);
        var doc=ContextDocuments.snapshot(base,"README.md","a".repeat(40));var chunks=ContextDocuments.chunks(List.of(doc));
        assertEquals(2,chunks.size());assertEquals(1,chunks.getFirst().startLine());assertEquals(2,chunks.getFirst().endLine());assertEquals(3,chunks.getLast().startLine());assertEquals(4,chunks.getLast().endLine());
        assertEquals("SNAPSHOT_DOCUMENT",chunks.getFirst().origin());assertEquals("SOURCE_LINE",chunks.getFirst().lineKind());assertEquals("a".repeat(40),chunks.getFirst().revision());
        assertEquals(ContextDocuments.hash(body),chunks.getFirst().documentHash());assertEquals(ContextDocuments.hash(chunks.getFirst().text()),chunks.getFirst().chunkHash());assertEquals(body,Files.readString(base.resolve("README.md")));
    }
    @Test void historicalDiffLocationsRemainDiffLocations(){
        var doc=new ContextDocuments.Document("HISTORICAL_DIFF","git:abc:diff","abc","DIFF_LINE","change","@@ -1 +1 @@\n-old\n+new\n");
        var chunk=ContextDocuments.chunks(List.of(doc)).getFirst();assertEquals("DIFF_LINE",chunk.lineKind());assertEquals("HISTORICAL_DIFF",chunk.origin());assertTrue(chunk.text().contains("-old"));
    }
    @Test void rejectsTraversalAndCredentialFilesButAllowsEnvironmentPlaceholders() throws Exception {
        assertFalse(ContextDocuments.supported(".env"));assertFalse(ContextDocuments.supported("settings.xml"));assertFalse(ContextDocuments.supported("credentials.json"));
        assertThrows(IllegalArgumentException.class,()->ContextDocuments.snapshot(base,"../outside.md","commit"));
        Files.writeString(base.resolve("application.properties"),"database.password=${POSTGRES_PASSWORD}\n");
        assertEquals("SNAPSHOT_CONFIG",ContextDocuments.snapshot(base,"application.properties","commit").origin());
        Files.writeString(base.resolve("application.properties"),"database.password=literal-secret\n");assertThrows(IllegalArgumentException.class,()->ContextDocuments.snapshot(base,"application.properties","commit"));
    }
    @Test void unchangedInputsKeepHashesAndChangedChunksInvalidateIndependently(){
        String longBody=("A payment documentation line.\n").repeat(70);var doc=new ContextDocuments.Document("SNAPSHOT_DOCUMENT","README.md","a","SOURCE_LINE","README",longBody);
        var before=ContextDocuments.chunks(List.of(doc));var after=ContextDocuments.chunks(List.of(new ContextDocuments.Document(doc.origin(),doc.sourceRef(),doc.revision(),doc.lineKind(),doc.title(),longBody+"Retry safely.\n")));
        assertEquals(before.getFirst().chunkHash(),after.getFirst().chunkHash());assertNotEquals(before.getFirst().documentHash(),after.getFirst().documentHash());
        assertNotEquals(ContextDocuments.hash(ContextDocuments.prompt(before.getLast())),ContextDocuments.hash(ContextDocuments.prompt(after.getLast())));
    }
}
