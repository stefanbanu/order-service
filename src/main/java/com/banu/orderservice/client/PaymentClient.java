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
     * and must report the outcome of the first. Reporting it means the replay
     * reproduces the original outcome through the only channel a {@code void}
     * method has — a replay of a <em>successful</em> refund must return normally,
     * and a replay of a <em>failed</em> one must throw again. A replayed failure
     * that returns quietly would have {@link
     * com.banu.orderservice.service.OrderService#cancel} commit a cancellation for
     * money that never moved.
     *
     * <p>In exchange the caller keeps the key stable across retries of the same
     * logical cancellation. That stability rests on the order's id plus its
     * immutable {@code createdAt} — see
     * {@link com.banu.orderservice.service.OrderService#cancel} for the
     * derivation — and nothing mutable, so a refund that was actually processed
     * but whose response never arrived is not paid out again on retry.
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
