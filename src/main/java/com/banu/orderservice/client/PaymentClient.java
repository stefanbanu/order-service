package com.banu.orderservice.client;

import java.math.BigDecimal;

/**
 * Outbound port for the payment provider. Lives in its own package rather than
 * under controller/service/repository because it is not one of those layers: it
 * is the edge the service calls out through, and keeping it separate means the
 * refund can be faked in tests and swapped for a real provider without touching
 * OrderService.
 */
public interface PaymentClient {

    /**
     * Refunds a cancelled order in full.
     *
     * @throws PaymentException if the refund could not be completed
     */
    void refund(Long orderId, BigDecimal amount);
}
