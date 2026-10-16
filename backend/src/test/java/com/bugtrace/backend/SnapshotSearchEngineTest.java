package com.bugtrace.backend;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.bugtrace.backend.SnapshotSearchEngine.*;

class SnapshotSearchEngineTest {
    @TempDir Path base;
    Path fixture() throws Exception {
        Path path=base.resolve("src/main/java/demo/CheckoutService.java");Files.createDirectories(path.getParent());
        Files.writeString(path,"""
            package demo;
            // retry after timeout must preserve idempotency
            class CheckoutService { void charge() {} }
            """);return path;
    }
    @Test void searchesAllFiveExampleKeywordsAndPreservesSource() throws Exception {
        fixture();String hash=GitSnapshot.contentHash(base);var index=new JavaSymbolIndexer().index(base);var engine=new SnapshotSearchEngine();
        for(String word:new String[]{"checkout","retry","timeout","charge","idempotency"}) {
            var query=Query.create(word,Kind.ALL,50);var matches=new Matches(50);
            engine.symbols(index,query,matches);engine.source(base,query,matches);
            assertTrue(matches.totalMatches()>0,word);
        }
        assertEquals(hash,GitSnapshot.contentHash(base));
    }
    @Test void filtersClassMethodAndWindowsStylePathsCaseInsensitively() throws Exception {
        fixture();var engine=new SnapshotSearchEngine();var index=new JavaSymbolIndexer().index(base);
        var classes=new Matches(50);engine.symbols(index,Query.create("CHECKOUT",Kind.CLASS,50),classes);
        assertEquals(1,classes.totalMatches());assertEquals("CLASS",classes.hits().getFirst().kind());
        var methods=new Matches(50);engine.symbols(index,Query.create("charge",Kind.METHOD,50),methods);
        assertEquals(1,methods.totalMatches());assertEquals("METHOD",methods.hits().getFirst().kind());
        var paths=new Matches(50);engine.source(base,Query.create("src\\main\\java",Kind.FILE,50),paths);
        assertEquals(1,paths.totalMatches());
    }
    @Test void treatsRegexCharactersLiterallyAndReturnsCorrectLogLine() throws Exception {
        var engine=new SnapshotSearchEngine();var query=Query.create("[timeout].*",Kind.ERROR,50);var matches=new Matches(50);
        engine.log("execution-1","EXECUTION_STDERR","first line\nError [timeout].* after payment\n",query,matches);
        assertEquals(1,matches.totalMatches());assertEquals(2,matches.hits().getFirst().line());assertEquals(7,matches.hits().getFirst().column());
        var noMatch=new Matches(50);engine.log("execution-1","EXECUTION_STDERR","timeout anything",query,noMatch);assertEquals(0,noMatch.totalMatches());
    }
    @Test void boundsResultsAndRanksSymbolsBeforeText() throws Exception {
        fixture();var engine=new SnapshotSearchEngine();var query=Query.create("charge",Kind.ALL,1);var matches=new Matches(1);
        engine.source(base,query,matches);engine.symbols(new JavaSymbolIndexer().index(base),query,matches);
        assertTrue(matches.totalMatches()>1);assertEquals(1,matches.hits().size());assertEquals("METHOD",matches.hits().getFirst().kind());
    }
    @Test void sourceTextStillSearchesBrokenJavaAndReportsInvalidUtf8() throws Exception {
        Files.writeString(base.resolve("Broken.java"),"class Broken { retry void ( }");
        Files.write(base.resolve("Bad.txt"),new byte[]{(byte)0xc3,0x28});
        var engine=new SnapshotSearchEngine();var matches=new Matches(50);var scan=engine.source(base,Query.create("retry",Kind.TEXT,50),matches);
        assertEquals(1,matches.totalMatches());assertEquals(1,scan.notices().size());
    }
    @Test void validatesQueryAndLimit() {
        assertThrows(IllegalArgumentException.class,()->Query.create("",Kind.ALL,50));
        assertThrows(IllegalArgumentException.class,()->Query.create("retry\n",Kind.ALL,0));
        assertThrows(IllegalArgumentException.class,()->Query.create("retry",Kind.ALL,101));
    }
}
