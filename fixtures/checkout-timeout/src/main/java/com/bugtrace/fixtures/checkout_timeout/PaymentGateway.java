package com.bugtrace.fixtures.checkout_timeout;

import java.math.BigDecimal;

public interface PaymentGateway {

    String charge(String orderId, BigDecimal amount);
}