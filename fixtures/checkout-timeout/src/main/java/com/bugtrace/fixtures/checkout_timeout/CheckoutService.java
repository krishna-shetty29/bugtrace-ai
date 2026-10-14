package com.bugtrace.fixtures.checkout_timeout;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class CheckoutService {

    private final PaymentGateway paymentGateway;
    private final PaymentLedger ledger;

    public CheckoutService(
            PaymentGateway paymentGateway,
            PaymentLedger ledger
    ) {
        this.paymentGateway = paymentGateway;
        this.ledger = ledger;
    }

    public CheckoutResponse checkout(CheckoutRequest request) {
        String paymentId = paymentGateway.charge(
                request.orderId(),
                request.amount()
        );

        CheckoutResponse response = new CheckoutResponse(
                request.orderId(),
                paymentId,
                "PAID",
                request.amount(),
                "USD"
        );

        ledger.record(response);
        return response;
    }

    public List<CheckoutResponse> payments() {
        return ledger.findAll();
    }
}