package com.bugtrace.fixtures.checkout_timeout;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CheckoutIdempotencyTest {

    @Test
    void retryAfterLostResponseMustNotCreateAnotherCharge() {
        // 1. Create one identifier for the logical checkout.
        String checkoutId = UUID.randomUUID().toString();

        PaymentLedger ledger = new PaymentLedger();

        // 2. Enable "charge succeeds, response lost".
        // Zero delay keeps this test fast and repeatable.
        FakePaymentGateway gateway =
                new FakePaymentGateway(ledger, 0, true);

        CheckoutService service =
                new CheckoutService(gateway, ledger);

        CheckoutRequest request = new CheckoutRequest(
                checkoutId,
                "order-test-001",
                new BigDecimal("49.99")
        );

        // 3. Submit checkout. The charge occurs before the timeout.
        assertThrows(
                PaymentGateway.ResponseLostException.class,
                () -> service.checkout(request)
        );

        assertEquals(
                1,
                ledger.findAll().size(),
                "The first attempt should already have recorded a charge"
        );

        // 4. Retry exactly the same logical checkout.
        CheckoutResponse response = service.checkout(request);

        assertEquals("PAID", response.status());

        // 5. Correct behavior requires exactly one charge.
        long chargeCount = ledger.findAll()
                .stream()
                .filter(charge ->
                        charge.checkoutId().equals(checkoutId)
                )
                .count();

        assertEquals(
                1L,
                chargeCount,
                "Retrying the same checkout must not charge twice"
        );
    }
}