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
     * Refunds a cancelled order in full, at most once per idempotency key.
     *
     * <p>The provider is <em>required</em> to treat two calls carrying the same
     * {@code idempotencyKey} as one refund: the second must move no further money
     * and must report the outcome of the first. In exchange the caller guarantees
     * the key is stable across retries of the same logical cancellation, so a
     * refund that was actually processed but whose response never arrived is not
     * paid out again when the client retries.
     *
     * <p>Any unchecked exception out of this method is treated as a refund
     * failure, {@link PaymentException} being this port's own signal for one; a
     * real implementation may also fail with its HTTP client's exceptions.
     *
     * @param idempotencyKey stable, non-null key identifying this refund attempt
     * @throws PaymentException if the refund could not be completed
     */
    void refund(Long orderId, BigDecimal amount, String idempotencyKey);
}
