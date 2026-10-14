package com.bugtrace.fixtures.checkout_timeout;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DuplicateChargeTest {

    @Test
    void retryAfterLostResponseCreatesTwoCharges() {
        PaymentLedger ledger = new PaymentLedger();

        FakePaymentGateway gateway =
                new FakePaymentGateway(ledger, 0, true);

        CheckoutService service =
                new CheckoutService(gateway, ledger);

        CheckoutRequest request = new CheckoutRequest(
                "checkout-001",
                "order-001",
                new BigDecimal("49.99")
        );

        assertThrows(
                PaymentGateway.ResponseLostException.class,
                () -> service.checkout(request)
        );

        assertEquals(1, ledger.findAll().size());

        CheckoutResponse response = service.checkout(request);

        assertEquals("PAID", response.status());
        assertEquals(2, ledger.findAll().size());

        var firstCharge = ledger.findAll().get(0);
        var secondCharge = ledger.findAll().get(1);

        assertEquals(
                firstCharge.checkoutId(),
                secondCharge.checkoutId()
        );

        assertEquals(firstCharge.amount(), secondCharge.amount());

        assertNotEquals(
                firstCharge.paymentId(),
                secondCharge.paymentId()
        );
    }
}