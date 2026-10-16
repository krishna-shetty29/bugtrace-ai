package com.bugtrace.backend;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JavaSymbolIndexerTest {
    @TempDir Path base;
    @Test void extractsKindsOverloadsNestedOwnersAndLineLocations() {
        String source="""
            package demo;
            import java.util.List;
            import static java.util.Collections.*;
            @Deprecated
            public class Checkout {
                @Deprecated private int first, second;
                public void pay() {}
                public void pay(String id) {}
                interface Gateway { void charge(); }
                class Nested { String name; }
            }
            """;
        var result=new JavaSymbolIndexer().parse("src/main/java/demo/Checkout.java",source);
        assertTrue(result.parsed());assertEquals("demo",result.packageName());
        var kinds=new HashSet<>(result.symbols().stream().map(JavaSymbolIndexer.Symbol::kind).toList());
        assertTrue(kinds.containsAll(Set.of("PACKAGE","IMPORT","CLASS","INTERFACE","METHOD","FIELD","ANNOTATION")));
        assertEquals(2,result.symbols().stream().filter(symbol->symbol.name().equals("pay")).count());
        var field=result.symbols().stream().filter(symbol->symbol.name().equals("second")).findFirst().orElseThrow();
        assertEquals(6,field.startLine());assertEquals("demo.Checkout",field.owner());assertEquals(List.of("Deprecated"),field.annotations());
        var nested=result.symbols().stream().filter(symbol->symbol.name().equals("name")).findFirst().orElseThrow();
        assertEquals("demo.Checkout.Nested",nested.owner());
        assertTrue(result.symbols().stream().allMatch(symbol->symbol.startLine()>0 && symbol.endLine()>=symbol.startLine()));
    }
    @Test void reportsBrokenFileWithoutDroppingItOrBlockingOtherFiles() throws Exception {
        Files.writeString(base.resolve("Good.java"),"class Good { void run() {} }");
        Files.writeString(base.resolve("Broken.java"),"class Broken { void run( }");
        var result=new JavaSymbolIndexer().index(base);
        assertEquals("PARTIAL",result.status());assertEquals(2,result.javaFiles());assertEquals(1,result.failedFiles());
        var broken=result.files().stream().filter(file->file.filePath().equals("Broken.java")).findFirst().orElseThrow();
        assertFalse(broken.parsed());assertFalse(broken.failures().isEmpty());assertTrue(broken.symbols().isEmpty());
        assertEquals("PARSE",broken.failures().getFirst().phase());
    }
    @Test void supportsRecordsEnumsAndAnnotationDeclarations() {
        var file=new JavaSymbolIndexer().parse("Types.java","""
            record Request(String id) { Request { } }
            enum State { CREATED, COMPLETED }
            @interface Marker { String value(); }
            """);
        assertTrue(file.parsed(),file.failures().toString());
        var kinds=new HashSet<>(file.symbols().stream().map(JavaSymbolIndexer.Symbol::kind).toList());
        assertTrue(kinds.containsAll(Set.of("RECORD","RECORD_COMPONENT","ENUM","ENUM_CONSTANT","ANNOTATION_TYPE","ANNOTATION_MEMBER","CONSTRUCTOR")));
    }
    @Test void scansMainAndTestsWithoutModifyingSource() throws Exception {
        Path main=base.resolve("src/main/java");Path tests=base.resolve("src/test/java");Files.createDirectories(main);Files.createDirectories(tests);
        Files.writeString(main.resolve("App.java"),"class App {}");Files.writeString(tests.resolve("AppTest.java"),"class AppTest {}");
        String before=GitSnapshot.contentHash(base);
        var result=new JavaSymbolIndexer().index(base);
        assertEquals(2,result.parsedFiles());assertEquals("COMPLETE",result.status());
        assertEquals(before,GitSnapshot.contentHash(base));
    }
    @Test void reportsNoJavaFilesAsEmpty() throws Exception {
        Files.writeString(base.resolve("README.md"),"No Java source");
        assertEquals("EMPTY",new JavaSymbolIndexer().index(base).status());
    }
}
