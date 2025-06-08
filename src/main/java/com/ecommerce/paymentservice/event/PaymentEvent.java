package com.ecommerce.paymentservice.event;

import lombok.Data;

import jakarta.validation.constraints.*;

@Data
public class PaymentEvent {
    @NotBlank(message = "Order ID cannot be empty")
    private String orderId;

    @NotBlank(message = "Status cannot be empty")
    @Pattern(regexp = "PAYMENT_RECEIVED|PAYMENT_FAILED", message = "Status must be PAYMENT_RECEIVED or PAYMENT_FAILED")
    private String status;

    private String reason; // For PAYMENT_FAILED
}