
package com.bugtrace.fixtures.checkout_timeout;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FakePaymentGateway implements PaymentGateway {

    private final long delayMs;
    private final AtomicLong sequence = new AtomicLong();

    public FakePaymentGateway(
            @Value("${fixture.payment-delay-ms:0}") long delayMs
    ) {
        if (delayMs < 0 || delayMs > 60000) {
            throw new IllegalArgumentException(
                    "Payment delay must be between 0 and 60000 milliseconds"
            );
        }

        this.delayMs = delayMs;
    }

    @Override
    public String charge(String orderId, BigDecimal amount) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Fake payment was interrupted", exception
            );
        }

        return "fake-payment-" + sequence.incrementAndGet();
    }
}