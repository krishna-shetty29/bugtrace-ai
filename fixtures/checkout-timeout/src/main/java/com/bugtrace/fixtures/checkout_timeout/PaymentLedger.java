package com.bugtrace.fixtures.checkout_timeout;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

@Component
public class PaymentLedger {

    public record Charge(
        String checkoutId,
        String orderId,
        String paymentId,
        BigDecimal amount,
        String currency
    ) {}

    private final CopyOnWriteArrayList<Charge> charges =
        new CopyOnWriteArrayList<>();

    public void record(Charge charge) {
        charges.add(charge);
    }

    public List<Charge> findAll() {
        return List.copyOf(charges);
    }
}