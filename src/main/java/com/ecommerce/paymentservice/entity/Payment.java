package com.ecommerce.paymentservice.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;

@Document(collection = "payments")
@Data
public class Payment {
    @Id
    private String id;

    @NotBlank(message = "Order ID cannot be empty")
    private String orderId;

    @NotNull(message = "Amount cannot be null")
    @Positive(message = "Amount must be positive")
    private BigDecimal amount;

    @NotBlank(message = "Payment method cannot be empty")
    @Pattern(regexp = "ONLINE|COD", message = "Payment method must be ONLINE or COD")
    private String paymentMethod;

    @NotBlank(message = "Status cannot be empty")
    @Pattern(regexp = "PENDING|SUCCESS|FAILED|EXPIRED|REFUNDED", message = "Status must be one of PENDING, SUCCESS, FAILED, EXPIRED, REFUNDED")
    private String status = "PENDING";

    @Pattern(regexp = "^(https?:\\/\\/)?([\\w\\-]+\\.)+[\\w\\-]+(\\/[\\w\\-./?%&=]*)?$", message = "Payment link must be a valid URL")
    private String paymentLink; // For ONLINE payments

    private LocalDateTime linkCreatedAt;

    private LocalDateTime linkExpiresAt;

    private String reason; // For FAILED or EXPIRED status

    // Custom validation for payment link and dates
    public void validate() {
        if ("ONLINE".equals(paymentMethod)) {
            if (paymentLink == null || paymentLink.isEmpty()) {
                throw new IllegalArgumentException("Payment link is required for ONLINE payments");
            }
            if (linkCreatedAt == null || linkExpiresAt == null) {
                throw new IllegalArgumentException("Link creation and expiry dates are required for ONLINE payments");
            }
            if (linkExpiresAt.isBefore(linkCreatedAt)) {
                throw new IllegalArgumentException("Link expiry date must be after creation date");
            }
        } else if ("COD".equals(paymentMethod)) {
            if (paymentLink != null || linkCreatedAt != null || linkExpiresAt != null) {
                throw new IllegalArgumentException("Payment link and dates are not applicable for COD payments");
            }
        }
    }
}