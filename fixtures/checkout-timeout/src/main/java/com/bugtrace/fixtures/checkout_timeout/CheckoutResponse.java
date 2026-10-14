package com.bugtrace.fixtures.checkout_timeout;

import java.math.BigDecimal;

public record CheckoutResponse(
        String orderId,
        String paymentId,
        String status,
        BigDecimal amount,
        String currency
) {}