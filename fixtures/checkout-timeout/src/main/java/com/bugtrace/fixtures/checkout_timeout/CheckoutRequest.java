package com.bugtrace.fixtures.checkout_timeout;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CheckoutRequest(
    @NotBlank @Size(max = 100)
    String checkoutId,

    @NotBlank @Size(max = 100)
    String orderId,

    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 10, fraction = 2)
    BigDecimal amount
) {}