package com.bugtrace.fixtures.checkout_timeout;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class CheckoutService {

    private final PaymentGateway gateway;
    private final PaymentLedger ledger;

    public CheckoutService(
        PaymentGateway gateway,
        PaymentLedger ledger
    ) {
        this.gateway = gateway;
        this.ledger = ledger;
    }

    public CheckoutResponse checkout(CheckoutRequest request) {
        String paymentId = gateway.charge(
            request.checkoutId(),
            request.orderId(),
            request.amount()
        );

        return new CheckoutResponse(
            request.checkoutId(),
            request.orderId(),
            paymentId,
            "PAID",
            request.amount(),
            "USD"
        );
    }

    public List<PaymentLedger.Charge> payments() {
        return ledger.findAll();
    }
}