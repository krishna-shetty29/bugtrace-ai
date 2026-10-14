package com.bugtrace.fixtures.checkout_timeout;

import java.util.List;

import jakarta.validation.Valid;

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
    public List<CheckoutResponse> payments() {
        return service.payments();
    }
}