package com.ecommerce.paymentservice.event;

import lombok.Data;

import jakarta.validation.constraints.*;

@Data
public class OrderEvent {
    @NotBlank(message = "Order ID cannot be empty")
    private String orderId;

    @NotBlank(message = "Status cannot be empty")
    @Pattern(regexp = "CANCELLED|RETURNED|DELIVERED", message = "Status must be CANCELLED, RETURNED, or DELIVERED")
    private String status;
}