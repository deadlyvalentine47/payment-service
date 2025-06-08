package com.ecommerce.paymentservice.service;

import com.ecommerce.paymentservice.entity.Payment;
import com.ecommerce.paymentservice.event.OrderEvent;
import com.ecommerce.paymentservice.event.PaymentEvent;
import com.ecommerce.paymentservice.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {
    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Validator validator; // For manual validation

    private static final String PAYMENT_EVENTS_TOPIC = "PAYMENT_EVENTS";

    public Payment initiatePayment(String orderId, BigDecimal amount, String paymentMethod) {
        // Validate inputs
        if (orderId == null || orderId.trim().isEmpty()) {
            throw new IllegalArgumentException("Order ID cannot be empty");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (!List.of("ONLINE", "COD").contains(paymentMethod)) {
            throw new IllegalArgumentException("Payment method must be ONLINE or COD");
        }

        // Check for duplicate payment
        List<Payment> existingPayments = paymentRepository.findByOrderId(orderId);
        if (!existingPayments.isEmpty()) {
            throw new IllegalStateException("Payment already initiated for order: " + orderId);
        }

        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setAmount(amount);
        payment.setPaymentMethod(paymentMethod);
        payment.setStatus("PENDING");

        if ("ONLINE".equals(paymentMethod)) {
            // Generate a payment link with 5-minute expiry
            String paymentLink = "https://payment-gateway.com/pay/" + UUID.randomUUID();
            payment.setPaymentLink(paymentLink);
            payment.setLinkCreatedAt(LocalDateTime.now());
            payment.setLinkExpiresAt(LocalDateTime.now().plusMinutes(5));
        }

        // Validate entity
        payment.validate();
        validateEntity(payment);

        return paymentRepository.save(payment);
    }

    @Retryable(value = { Exception.class }, maxAttempts = 3, backoff = @Backoff(delay = 1000))
    public Payment processPayment(String paymentId, boolean isSuccess, String failureReason) {
        // Validate inputs
        if (paymentId == null || paymentId.trim().isEmpty()) {
            throw new IllegalArgumentException("Payment ID cannot be empty");
        }
        if (!isSuccess && (failureReason == null || failureReason.trim().isEmpty())) {
            throw new IllegalArgumentException("Failure reason is required when payment fails");
        }

        List<Payment> payments = paymentRepository.findById(paymentId)
                .map(List::of)
                .orElseThrow(() -> new IllegalArgumentException("Payment with ID " + paymentId + " not found"));

        Payment payment = payments.get(0);

        if (!"PENDING".equals(payment.getStatus())) {
            log.warn("Payment {} is not in PENDING status, current status: {}", paymentId, payment.getStatus());
            throw new IllegalStateException("Payment is not in PENDING status, current status: " + payment.getStatus());
        }

        if ("ONLINE".equals(payment.getPaymentMethod()) && payment.getLinkExpiresAt().isBefore(LocalDateTime.now())) {
            payment.setStatus("EXPIRED");
            payment.setReason("payment link expired");
            payment = paymentRepository.save(payment);
            publishPaymentEvent(payment, "payment link expired");
            return payment;
        }

        if (isSuccess) {
            payment.setStatus("SUCCESS");
            payment = paymentRepository.save(payment);
            publishPaymentEvent(payment, null);
        } else {
            payment.setStatus("FAILED");
            payment.setReason(failureReason);
            payment = paymentRepository.save(payment);
            publishPaymentEvent(payment, payment.getReason());
        }

        // Validate updated entity
        validateEntity(payment);

        return payment;
    }

    private void publishPaymentEvent(Payment payment, String reason) {
        PaymentEvent event = new PaymentEvent();
        event.setOrderId(payment.getOrderId());
        event.setStatus("SUCCESS".equals(payment.getStatus()) ? "PAYMENT_RECEIVED" : "PAYMENT_FAILED");
        event.setReason(reason);
        validateEntity(event);
        kafkaTemplate.send(PAYMENT_EVENTS_TOPIC, event);
        log.info("Published payment event: {}", event);
    }

    @Scheduled(fixedRate = 60000) // Run every minute
    public void checkPaymentLinkExpiry() {
        List<Payment> pendingPayments = paymentRepository.findByStatus("PENDING");
        LocalDateTime now = LocalDateTime.now();
        for (Payment payment : pendingPayments) {
            if ("ONLINE".equals(payment.getPaymentMethod()) &&
                    payment.getLinkExpiresAt() != null &&
                    payment.getLinkExpiresAt().isBefore(now)) {
                log.info("Payment {} link expired, marking as EXPIRED", payment.getId());
                payment.setStatus("EXPIRED");
                payment.setReason("payment link expired");
                payment = paymentRepository.save(payment);
                publishPaymentEvent(payment, "payment link expired");
            }
        }
    }

    public void handleOrderEvent(OrderEvent event) {
        // Validate event
        validateEntity(event);

        log.info("Processing order event for orderId: {}", event.getOrderId());
        List<Payment> payments = paymentRepository.findByOrderId(event.getOrderId());
        if (payments.isEmpty()) {
            log.warn("Payment not found for orderId: {}", event.getOrderId());
            throw new IllegalStateException("Payment not found for order: " + event.getOrderId());
        }
        Payment payment = payments.get(0);
        log.info("Current payment status: {}", payment.getStatus());

        if ("CANCELLED".equals(event.getStatus()) || "RETURNED".equals(event.getStatus())) {
            if ("SUCCESS".equals(payment.getStatus()) && !"REFUNDED".equals(payment.getStatus())) {
                log.info("Initiating refund for payment {} due to order status: {}", payment.getId(), event.getStatus());
                processRefund(payment);
            } else if (!"SUCCESS".equals(payment.getStatus())) {
                log.info("No refund required for payment {} in status: {}", payment.getId(), payment.getStatus());
            }
        } else if ("DELIVERED".equals(event.getStatus()) && "COD".equals(payment.getPaymentMethod())) {
            if ("PENDING".equals(payment.getStatus())) {
                log.info("Marking COD payment {} as SUCCESS for delivered order", payment.getId());
                payment.setStatus("SUCCESS");
                paymentRepository.save(payment);
                // No need to publish PAYMENT_RECEIVED for COD (Order Service already marks as PLACED)
            } else {
                log.warn("COD payment {} is not in PENDING status, current status: {}", payment.getId(), payment.getStatus());
            }
        }
    }

    @Retryable(value = { Exception.class }, maxAttempts = 3, backoff = @Backoff(delay = 1000))
    private void processRefund(Payment payment) {
        if (!"SUCCESS".equals(payment.getStatus())) {
            throw new IllegalStateException("Cannot refund payment in status: " + payment.getStatus());
        }
        log.info("Processing refund for payment {} of amount {}", payment.getId(), payment.getAmount());
        payment.setStatus("REFUNDED");
        paymentRepository.save(payment);
    }

    private void validateEntity(Object entity) {
        Set<ConstraintViolation<Object>> violations = validator.validate(entity);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }
}