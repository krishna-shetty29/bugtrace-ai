package com.bugtrace.fixtures.checkout_timeout;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CheckoutController {

    private final CheckoutService service;

    public CheckoutController(CheckoutService service) {
        this.service = service;
    }

    @PostMapping("/checkout")
    public CheckoutResponse checkout(
        @Valid @RequestBody CheckoutRequest request
    ) {
        return service.checkout(request);
    }

    @GetMapping("/payments")
    public List<PaymentLedger.Charge> payments() {
        return service.payments();
    }

    @ExceptionHandler(PaymentGateway.ResponseLostException.class)
    public ResponseEntity<Map<String, String>> responseLost(
        PaymentGateway.ResponseLostException exception
    ) {
        return ResponseEntity.status(504).body(Map.of(
            "code", "PAYMENT_RESPONSE_TIMEOUT",
            "message", exception.getMessage()
        ));
    }
}