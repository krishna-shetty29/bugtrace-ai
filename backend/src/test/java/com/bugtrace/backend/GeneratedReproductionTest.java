package com.bugtrace.backend;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedReproductionTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private GeneratedReproduction.Spec spec(String source){return new GeneratedReproduction.Spec(source,GeneratedReproduction.ASSERTION,GeneratedReproduction.FAULT,List.of("e1"),GeneratedReproduction.SIGNATURE,"Test explanation");}
    @Test void acceptsScaffoldAndPinnedEvidence(){assertEquals(List.of("e1"),GeneratedReproduction.parse(json.writeValueAsString(spec(GeneratedReproduction.exampleSource())),Set.of("e1")).evidenceIds());}
    @Test void rejectsWeakAssertionOrRemovedFault(){
        assertThrows(IllegalArgumentException.class,()->GeneratedReproduction.validateSource(GeneratedReproduction.exampleSource().replace("assertEquals(1L, chargeCount", "assertEquals(2L, chargeCount")));
        assertThrows(IllegalArgumentException.class,()->GeneratedReproduction.validateSource(GeneratedReproduction.exampleSource().replace("ledger, 0, true", "ledger, 0, false")));
    }
    @Test void rejectsSkippedTestWrongClassAndSyntheticThrow(){
        for(String source:List.of(GeneratedReproduction.exampleSource().replace("@Test","@Disabled @Test"),GeneratedReproduction.exampleSource().replace(GeneratedReproduction.CLASS,"OtherTest"),GeneratedReproduction.exampleSource().replace("String checkoutId", "throw new AssertionError(); String checkoutId")))assertThrows(IllegalArgumentException.class,()->GeneratedReproduction.validateSource(source));
    }
    @Test void rejectsChangedContractAndUnknownCitations(){
        String document=json.writeValueAsString(spec(GeneratedReproduction.exampleSource()));
        assertThrows(IllegalArgumentException.class,()->GeneratedReproduction.parse(document.replace(GeneratedReproduction.ASSERTION,"Two charges are fine"),Set.of("e1")));
        assertThrows(IllegalArgumentException.class,()->GeneratedReproduction.parse(document,Set.of("other")));
    }
    @Test void javaSyntaxCheckDoesNotPretendToCompile(){
        // Unknown constructor argument is valid Java syntax, but must fail real test compilation.
        assertDoesNotThrow(()->GeneratedReproduction.validateSource(GeneratedReproduction.exampleSource().replace("ledger, 0, true", "ledger, 0, true, unknownArgument")));
    }
    @Test void overlayHasFixedPathAndExactSourceHash(){
        var value=spec(GeneratedReproduction.exampleSource());var overlay=GeneratedReproduction.overlay(value);
        assertEquals(GeneratedReproduction.PATH,overlay.get("path"));assertEquals(ContextDocuments.hash(value.testSource()),overlay.get("sourceSha256"));
    }
}
