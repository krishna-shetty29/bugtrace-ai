package com.bugtrace.fixtures.checkout_timeout;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FakePaymentGateway implements PaymentGateway {

    private final PaymentLedger ledger;
    private final long delayMs;
    private final boolean loseFirstResponse;

    private final AtomicLong sequence = new AtomicLong();

    private final Set<String> attemptedCheckouts =
        ConcurrentHashMap.newKeySet();

    public FakePaymentGateway(
        PaymentLedger ledger,
        @Value("${fixture.payment-delay-ms:0}") long delayMs,
        @Value("${fixture.lose-first-response:false}")
        boolean loseFirstResponse
    ) {
        if (delayMs < 0 || delayMs > 60000) {
            throw new IllegalArgumentException(
                "Payment delay must be between 0 and 60000 milliseconds"
            );
        }

        this.ledger = ledger;
        this.delayMs = delayMs;
        this.loseFirstResponse = loseFirstResponse;
    }

    @Override
    public String charge(
        String checkoutId,
        String orderId,
        BigDecimal amount
    ) {
        boolean firstAttempt = attemptedCheckouts.add(checkoutId);

        String paymentId =
            "fake-payment-" + sequence.incrementAndGet();

        // Simulate the payment being accepted before its response arrives.
        ledger.record(new PaymentLedger.Charge(
            checkoutId,
            orderId,
            paymentId,
            amount,
            "USD"
        ));

        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                "Fake payment interrupted after charge", exception
            );
        }

        if (loseFirstResponse && firstAttempt) {
            throw new PaymentGateway.ResponseLostException();
        }

        return paymentId;
    }
}