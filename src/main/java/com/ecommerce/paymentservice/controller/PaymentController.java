package com.ecommerce.paymentservice.controller;

import com.ecommerce.paymentservice.entity.Payment;
import com.ecommerce.paymentservice.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Validated
public class PaymentController {
    private final PaymentService paymentService;

    @PostMapping("/initiate")
    public ResponseEntity<Payment> initiatePayment(
            @RequestParam @NotBlank(message = "Order ID cannot be empty") String orderId,
            @RequestParam @NotNull(message = "Amount cannot be null") @Positive(message = "Amount must be positive") BigDecimal amount,
            @RequestParam @NotBlank(message = "Payment method cannot be empty") @Pattern(regexp = "ONLINE|COD", message = "Payment method must be ONLINE or COD") String paymentMethod
    ) {
        Payment payment = paymentService.initiatePayment(orderId, amount, paymentMethod);
        return new ResponseEntity<>(payment, HttpStatus.CREATED);
    }

    @PostMapping("/{id}/process")
    public ResponseEntity<Payment> processPayment(
            @PathVariable @NotBlank(message = "Payment ID cannot be empty") String id,
            @RequestParam boolean isSuccess,
            @RequestParam(required = false) String failureReason
    ) {
        Payment payment = paymentService.processPayment(id, isSuccess, failureReason);
        return ResponseEntity.ok(payment);
    }

    // Handle validation errors
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<String> handleValidationExceptions(ConstraintViolationException ex) {
        StringBuilder errorMessage = new StringBuilder();
        ex.getConstraintViolations().forEach(violation ->
                errorMessage.append(violation.getMessage()).append("; ")
        );
        return new ResponseEntity<>(errorMessage.toString(), HttpStatus.BAD_REQUEST);
    }
}