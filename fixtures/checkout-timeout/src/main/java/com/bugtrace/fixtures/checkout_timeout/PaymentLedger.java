
package com.bugtrace.fixtures.checkout_timeout;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class PaymentLedger {

    private final ConcurrentHashMap<String, CheckoutResponse> payments =
            new ConcurrentHashMap<>();

    public void record(CheckoutResponse response) {
        payments.put(response.paymentId(), response);
    }

    public List<CheckoutResponse> findAll() {
        return payments.values()
                .stream()
                .sorted(Comparator.comparing(CheckoutResponse::paymentId))
                .toList();
    }
}