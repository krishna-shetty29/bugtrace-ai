package com.bugtrace.fixtures.checkout_timeout;

import java.math.BigDecimal;

public interface PaymentGateway {

    String charge(
        String checkoutId,
        String orderId,
        BigDecimal amount
    );

    class ResponseLostException extends RuntimeException {

        public ResponseLostException() {
            super("Simulated payment response timeout after charge");
        }
    }
}