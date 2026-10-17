package com.bugtrace.backend;

import java.nio.file.*;
import java.net.URLClassLoader;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Component check against a small fake API, not execution of an imported repository. */
class ControlledScenarioHarnessTest {
    @TempDir Path root;
    static final String API="""
        package com.bugtrace.fixtures.checkout_timeout;
        record CheckoutRequest(String checkoutId,String orderId,java.math.BigDecimal amount) {}
        record CheckoutResponse(String status) {}
        interface PaymentGateway {
            String charge(String checkout,String order,java.math.BigDecimal amount);
            class ResponseLostException extends RuntimeException {}
        }
        class PaymentLedger {
            record Charge(String checkoutId) {}
            private final java.util.List<Charge> charges=new java.util.concurrent.CopyOnWriteArrayList<>();
            void record(Charge charge){charges.add(charge);}
            java.util.List<Charge> findAll(){return java.util.List.copyOf(charges);}
        }
        class FakePaymentGateway implements PaymentGateway {
            final PaymentLedger ledger;final boolean lost;final java.util.Set<String> attempted=java.util.concurrent.ConcurrentHashMap.newKeySet();
            FakePaymentGateway(PaymentLedger ledger,long delay,boolean lost){this.ledger=ledger;this.lost=lost;}
            public String charge(String checkout,String order,java.math.BigDecimal amount){boolean first=attempted.add(checkout);ledger.record(new PaymentLedger.Charge(checkout));if(first&&lost)throw new ResponseLostException();return "fake";}
        }
        class CheckoutService {
            final PaymentGateway gateway;
            CheckoutService(PaymentGateway gateway,PaymentLedger ledger){this.gateway=gateway;}
            CheckoutResponse checkout(CheckoutRequest request){gateway.charge(request.checkoutId(),request.orderId(),request.amount());return new CheckoutResponse("PAID");}
        }
        """;
    void check(String scenario,int expectedChargeCount) throws Exception {
        Path api=root.resolve("Api.java"),test=root.resolve("GeneratedCheckoutReproductionTest.java");Files.writeString(api,API);Files.writeString(test,ControlledScenarioSource.entryPoint(scenario,3)+ControlledScenarioSource.harness());
        var compiler=ToolProvider.getSystemJavaCompiler();assertNotNull(compiler,"Run component checks with JDK 21");
        assertEquals(0,compiler.run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",root.toString(),api.toString(),test.toString()));
        var output=new ByteArrayOutputStream();var previous=System.out;
        try(var loader=new URLClassLoader(new java.net.URL[]{root.toUri().toURL()},getClass().getClassLoader())) {
            var type=loader.loadClass(GeneratedReproduction.SELECTOR);var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);var instance=constructor.newInstance();var method=type.getDeclaredMethod("duplicateChargeOnRetry");method.setAccessible(true);
            try {System.setOut(new PrintStream(output, true,java.nio.charset.StandardCharsets.UTF_8));var error=assertThrows(InvocationTargetException.class,()->method.invoke(instance));assertInstanceOf(org.opentest4j.AssertionFailedError.class,error.getCause());assertTrue(error.getCause().getMessage().contains("BUGTRACE_SCENARIO_DUPLICATES"));}
            finally {System.setOut(previous);}
        }
        var summary=ControlledScenarioReport.parse(output.toString(java.nio.charset.StandardCharsets.UTF_8),scenario,3);
        assertEquals(0,summary.errors());assertEquals(3,summary.duplicates());assertEquals(List.of(expectedChargeCount,expectedChargeCount,expectedChargeCount),summary.chargeCounts());
    }
    @Test void responseLossUsesThreeIsolatedRepetitions() throws Exception {check("RETRY_AFTER_RESPONSE_LOSS",2);}
    @Test void delayObservesTimeoutWithoutCancellingRequest() throws Exception {check("DELAYED_RESPONSE",2);}
    @Test void barrierCoordinatesTwoRetriesAfterOriginalCharge() throws Exception {check("CONCURRENT_RETRIES",3);}
    @Test void completedCheckoutRetryStillCountsCharges() throws Exception {check("RETRY_AFTER_COMPLETION",2);}
}
