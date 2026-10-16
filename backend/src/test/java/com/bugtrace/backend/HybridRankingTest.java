package com.bugtrace.backend;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HybridRankingTest {
    private HybridRanking.Candidate candidate(String key){return new HybridRanking.Candidate(key,"A.java",1,3,key,"code",null,null);}
    @Test void combinesRanksDeduplicatesAndDoesNotCountDuplicateHitsInOneChannel(){
        var a=candidate("a");var b=candidate("b");
        var result=HybridRanking.fuse(Map.of("EXACT",List.of(a,a,b),"SEMANTIC",List.of(b)),10);
        assertEquals("b",result.getFirst().context().key());assertEquals(2,result.size());
        assertEquals(Map.of("EXACT",2,"SEMANTIC",1),result.getFirst().channels());
        assertEquals(1.0/61+1.0/62,result.getFirst().score(),1e-10);
    }
    @Test void mapsLexicalHitToTheSameSemanticChunk(){
        var chunk=new SemanticChunks.Chunk("chunk","A.java",2,6,"METHOD","pay",false,"code","hash");
        var hit=new SnapshotSearchEngine.Hit("METHOD","SYMBOL","A.java","",3,1,"pay","snippet",110);
        assertEquals("chunk",SemanticRetrievalService.fromHit(hit,List.of(chunk)).key());
        var log=new SnapshotSearchEngine.Hit("ERROR","EXECUTION_STDOUT","","job",3,1,"failure","expected",70);
        assertEquals("job",SemanticRetrievalService.fromHit(log,List.of(chunk)).executionId());
    }
    @Test void preservesUncertainCallerAndTestEdges(){
        var caller=new JavaRelationshipAnalyzer.Vertex("caller","METHOD","Test.retry()","Test.java",1,3);
        var callee=new JavaRelationshipAnalyzer.Vertex("callee","METHOD","Gateway.charge()","Gateway.java",1,3);
        var edge=new JavaRelationshipAnalyzer.Edge("e","CALLS","caller","callee","POSSIBLE","syntax candidate","Test.java",2,1);
        var graph=new JavaRelationshipAnalyzer.Graph("1","3", "COMPLETE",java.time.Instant.now(),2,0,1,List.of(caller,callee),List.of(edge),List.of(),List.of());
        var a=new SemanticChunks.Chunk("a","Test.java",1,3,"METHOD","retry",true,"retry","h");
        var b=new SemanticChunks.Chunk("b","Gateway.java",1,3,"METHOD","charge",false,"charge","h");
        var ranked=new HybridRanking.Ranked(SemanticRetrievalService.candidate(b,null),1,Map.of("SEMANTIC",1));
        var nearby=SemanticRetrievalService.neighbors(graph,List.of(ranked),List.of(a,b));
        assertEquals("POSSIBLE",nearby.getFirst().confidence());assertEquals("retry",nearby.getFirst().context().name());
    }
}
