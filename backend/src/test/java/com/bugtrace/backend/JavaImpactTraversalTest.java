package com.bugtrace.backend;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaImpactTraversalTest {
    JavaRelationshipAnalyzer.Vertex node(String id) { return new JavaRelationshipAnalyzer.Vertex(id,"METHOD",id,"Test.java",1,1); }
    JavaRelationshipAnalyzer.Edge edge(String from,String to,String confidence) { return new JavaRelationshipAnalyzer.Edge(from+to,"CALLS",from,to,confidence,"test","Test.java",1,1); }
    JavaRelationshipAnalyzer.Graph graph() { return new JavaRelationshipAnalyzer.Graph("1","3.28.2","COMPLETE",Instant.now(),1,0,2,
        List.of(node("A"),node("B"),node("C")),List.of(edge("A","B","POSSIBLE"),edge("C","A","DECLARED"),edge("B","C","POSSIBLE")),List.of(),List.of()); }
    @Test void traversesCyclesOnceAndPreservesUncertaintyOnEveryPath() {
        var impact=JavaImpactTraversal.incoming(graph(),"B",5,true);
        assertEquals(2,impact.reached().size());assertTrue(impact.reached().stream().allMatch(JavaImpactTraversal.Reached::uncertain));
        assertEquals(2,impact.reached().getLast().path().size());
    }
    @Test void appliesDepthAndDeclaredOnlyFilters() {
        assertEquals(1,JavaImpactTraversal.incoming(graph(),"B",1,true).reached().size());
        assertTrue(JavaImpactTraversal.incoming(graph(),"B",3,false).reached().isEmpty());
        assertEquals("C",JavaImpactTraversal.incoming(graph(),"A",3,false).reached().getFirst().nodeId());
        assertThrows(IllegalArgumentException.class,()->JavaImpactTraversal.incoming(graph(),"A",6,true));
    }
}
