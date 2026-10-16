package com.bugtrace.backend;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JavaRelationshipAnalyzerTest {
    @TempDir Path base;
    void source(String name,String content) throws Exception { Path file=base.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,content); }
    JavaRelationshipAnalyzer.Vertex node(JavaRelationshipAnalyzer.Graph graph,String kind,String label) {
        return graph.nodes().stream().filter(node->node.kind().equals(kind)&&node.label().contains(label)).findFirst().orElseThrow();
    }
    @Test void connectsControllerServiceInterfaceImplementationAndLedgerConservatively() throws Exception {
        source("src/main/java/demo/Checkout.java","""
            package demo;
            interface PaymentGateway { String charge(String id); }
            class PaymentLedger { String record(String id) { return id; } }
            class FakePaymentGateway implements PaymentGateway {
                PaymentLedger ledger;
                public String charge(String id) { return ledger.record(id); }
            }
            class CheckoutService {
                PaymentGateway gateway;
                String checkout(String id) { return gateway.charge(id); }
            }
            class CheckoutController {
                CheckoutService service;
                String checkout(String id) { return service.checkout(id); }
            }
            """);
        String before=GitSnapshot.contentHash(base);
        var graph=new JavaRelationshipAnalyzer().analyze(base);
        var controller=node(graph,"METHOD","CheckoutController.");var service=node(graph,"METHOD","CheckoutService.");
        var gateway=node(graph,"METHOD","PaymentGateway.");var fake=node(graph,"METHOD","FakePaymentGateway.");var ledger=node(graph,"METHOD","PaymentLedger.");
        assertEdge(graph,"CALLS",controller.id(),service.id(),"POSSIBLE");
        assertEdge(graph,"CALLS",service.id(),gateway.id(),"POSSIBLE");
        assertEdge(graph,"CALLS",service.id(),fake.id(),"POSSIBLE");
        assertEdge(graph,"CALLS",fake.id(),ledger.id(),"POSSIBLE");
        assertTrue(graph.edges().stream().anyMatch(edge->edge.kind().equals("IMPLEMENTS")&&edge.confidence().equals("DECLARED")));
        assertTrue(graph.edges().stream().anyMatch(edge->edge.kind().equals("OVERRIDES_OR_IMPLEMENTS")&&edge.confidence().equals("POSSIBLE")));
        assertEquals(before,GitSnapshot.contentHash(base));
    }
    @Test void retainsEverySameArityOverloadAsPossibleRatherThanChoosingOne() throws Exception {
        source("src/main/java/demo/A.java","package demo; class A { void send(String a) {} void send(Integer a) {} void run(){ send(null); } }");
        var graph=new JavaRelationshipAnalyzer().analyze(base);
        var calls=graph.edges().stream().filter(edge->edge.kind().equals("CALLS")).toList();
        assertEquals(2,calls.size());assertTrue(calls.stream().allMatch(edge->edge.confidence().equals("POSSIBLE")));
    }
    @Test void retainsReflectiveExternalChainedCallsAndMethodReferencesAsUnresolved() throws Exception {
        source("src/main/java/A.java","""
            class A {
                Object value;
                void run() throws Exception {
                    Class.forName("demo.Plugin");
                    value.toString().trim();
                    Runnable task = this::run;
                }
            }
            """);
        var graph=new JavaRelationshipAnalyzer().analyze(base);
        assertTrue(graph.edges().stream().anyMatch(edge->edge.kind().equals("CALLS")&&edge.confidence().equals("UNRESOLVED")));
        assertTrue(graph.edges().stream().anyMatch(edge->edge.kind().equals("METHOD_REFERENCE")&&edge.confidence().equals("UNRESOLVED")));
        assertTrue(graph.uncertainEdges()>0);
    }
    @Test void recordsTestReferencesWithoutClaimingCoverage() throws Exception {
        source("src/main/java/demo/Service.java","package demo; class Service { void run() {} }");
        source("src/test/java/demo/ServiceTest.java","package demo; class ServiceTest { void test(){ Service service=new Service(); service.run(); } }");
        var graph=new JavaRelationshipAnalyzer().analyze(base);
        var run=node(graph,"METHOD","demo.Service.");
        assertTrue(graph.edges().stream().anyMatch(edge->edge.kind().equals("TEST_REFERENCES")&&edge.to().equals(run.id())&&edge.reason().contains("not observed coverage")));
    }
    @Test void keepsParseFailuresAndValidGraphWithStableNodeIds() throws Exception {
        source("src/main/java/A.java","class A extends Missing { void run(){ run(); } }");
        source("src/main/java/Broken.java","class Broken { void bad( }");
        var analyzer=new JavaRelationshipAnalyzer();var first=analyzer.analyze(base);var second=analyzer.analyze(base);
        assertEquals("PARTIAL",first.status());assertEquals(1,first.failedFiles());assertEquals(1,first.diagnostics().size());
        assertEquals(first.nodes(),second.nodes());assertEquals(first.edges(),second.edges());
        assertTrue(first.edges().stream().anyMatch(edge->edge.kind().equals("EXTENDS")&&edge.confidence().equals("UNRESOLVED")));
    }
    @Test void anonymousCallsAreNotInventedAsOuterClassCalls() throws Exception {
        source("src/main/java/A.java","class A { void target() {} void run(){ Runnable r=new Runnable(){public void run(){ target(); }}; } }");
        var graph=new JavaRelationshipAnalyzer().analyze(base);
        assertTrue(graph.edges().stream().anyMatch(edge->edge.kind().equals("CALLS")&&edge.confidence().equals("UNRESOLVED")&&edge.reason().contains("Anonymous")));
    }
    private void assertEdge(JavaRelationshipAnalyzer.Graph graph,String kind,String from,String to,String confidence) {
        assertTrue(graph.edges().stream().anyMatch(edge->edge.kind().equals(kind)&&edge.from().equals(from)&&edge.to().equals(to)&&edge.confidence().equals(confidence)),kind+" "+from+" -> "+to);
    }
}
