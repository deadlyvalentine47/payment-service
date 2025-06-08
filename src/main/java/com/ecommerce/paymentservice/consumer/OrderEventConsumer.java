package com.ecommerce.paymentservice.consumer;

import com.ecommerce.paymentservice.event.OrderEvent;
import com.ecommerce.paymentservice.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventConsumer {
    private final PaymentService paymentService;

    @KafkaListener(
            topics = "ORDER_EVENTS",
            groupId = "payment-service-group",
            containerFactory = "orderKafkaListenerContainerFactory"
    )
    public void handleOrderEvent(OrderEvent event) {
        if (event == null || event.getOrderId() == null || event.getStatus() == null) {
            log.error("Invalid order event received: {}", event);
            return;
        }
        log.info("Received order event: {}", event);
        try {
            paymentService.handleOrderEvent(event);
        } catch (Exception e) {
            log.error("Error processing order event: {}", event, e);
        }
    }
}