package com.banu.orderservice.service;

import com.banu.orderservice.client.PaymentClient;
import com.banu.orderservice.dto.OrderRequest;
import com.banu.orderservice.dto.OrderResponse;
import com.banu.orderservice.entity.Order;
import com.banu.orderservice.entity.OrderStatus;
import com.banu.orderservice.exception.InvalidStatusTransitionException;
import com.banu.orderservice.exception.OrderNotFoundException;
import com.banu.orderservice.exception.RefundFailedException;
import com.banu.orderservice.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class OrderService {

    private final OrderRepository repository;
    private final PaymentClient paymentClient;

    public OrderService(OrderRepository repository, PaymentClient paymentClient) {
        this.repository = repository;
        this.paymentClient = paymentClient;
    }

    @Transactional
    public OrderResponse create(OrderRequest request) {
        Order order = new Order();
        order.setCustomerEmail(request.customerEmail());
        order.setAmount(request.amount());
        order.setStatus(OrderStatus.NEW);
        return OrderResponse.from(repository.save(order));
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(Long id) {
        return repository.findById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    /**
     * The transition matrix lives on {@link OrderStatus}; this only enforces it.
     * A disallowed move throws rather than quietly leaving the status alone, so a
     * client that PATCHes a shipped order learns nothing happened.
     *
     * <p>Asking for the status an order already has is idempotent: it returns the
     * order unchanged, with no write, so a client retrying after a timeout gets
     * the same 200 the first attempt would have. That is handled here rather than
     * by giving OrderStatus self-edges, which would put the current status into
     * the "allowed" list of every refusal message.
     *
     * <p>A PATCH to CANCELLED delegates to {@link #cancel}, which is the single
     * path to CANCELLED. The matrix allows {@code PAID -> CANCELLED}, so without
     * the delegation a PATCH would reach CANCELLED without refunding and without
     * stamping {@code cancelledAt}/{@code cancellationReason} — and because
     * {@code cancel} then sees an already-CANCELLED order and returns its
     * idempotent 200, the refund would be permanently unreachable. Delegating
     * keeps the refund and the stamping impossible to bypass. It runs before the
     * same-status no-op and before the matrix check; the no-op survives because
     * {@code cancel} has its own already-CANCELLED early return, and a refused
     * transition such as {@code SHIPPED -> CANCELLED} still 409s because
     * {@code cancel} consults the same matrix.
     */
    @Transactional
    public OrderResponse updateStatus(Long id, OrderStatus target) {
        if (target == OrderStatus.CANCELLED) {
            return cancel(id, null);
        }
        Order order = repository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        if (order.getStatus() == target) {
            return OrderResponse.from(order);
        }
        if (!order.getStatus().canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(id, order.getStatus(), target);
        }
        order.setStatus(target);
        return OrderResponse.from(repository.save(order));
    }

    /**
     * Cancels an order, refunding it first if it was already paid. NEW is
     * cancelled outright, PAID is refunded, and SHIPPED is refused by the
     * {@link OrderStatus} matrix with the same 409 a PATCH would give.
     *
     * <p>An order that is already CANCELLED returns unchanged, exactly as
     * {@link #updateStatus} treats a self-patch: no write, and no second refund,
     * so a client retrying after a timeout is safe.
     *
     * <p>A <em>sequential</em> retry — the refund went through but the commit
     * failed, or the provider processed a refund whose response timed out and
     * surfaced as a PaymentException, so the transaction rolled back and the order
     * is still PAID — is deduplicated by the provider, through the idempotency key
     * {@code "order-" + id + "-v" + version}. A rolled-back attempt leaves the row
     * at the version it was loaded at, so the retry loads that same version and
     * reproduces the same key, and the provider sees one refund rather than two.
     * Had the cancellation instead committed, the retry short-circuits on the
     * already-CANCELLED early return above and never refunds at all. The key is
     * therefore read off the loaded entity <em>before</em> the flush below, which
     * increments the in-memory version; using the post-flush version, a timestamp
     * or a random UUID would mint a fresh key per attempt and refund twice.
     *
     * <p>The ordering below is load-bearing. The save is flushed <em>before</em>
     * the refund because the flush issues
     * {@code update orders ... where id=? and version=?} and holds the row lock
     * until commit: a second concurrent cancel blocks there, then fails its
     * version check and answers 409 without ever reaching the refund. Refunding
     * first would let both requests refund the same order.
     *
     * <p>The refund stays inside the transaction on purpose, which is the
     * trade-off a reviewer will ask about: an external call inside
     * {@code @Transactional} holds a database row for the duration of the call.
     * It is accepted because the requirement is that a failed refund leaves the
     * order PAID, and only a rollback delivers that — committing the cancellation
     * first and refunding afterwards would strand orders CANCELLED but unrefunded.
     * If the provider ever becomes slow enough to matter, the fix is an outbox,
     * not reordering these two lines.
     */
    @Transactional
    public OrderResponse cancel(Long id, String reason) {
        Order order = repository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return OrderResponse.from(order);
        }
        if (!order.getStatus().canTransitionTo(OrderStatus.CANCELLED)) {
            throw new InvalidStatusTransitionException(id, order.getStatus(), OrderStatus.CANCELLED);
        }
        boolean refundable = order.getStatus() == OrderStatus.PAID;
        BigDecimal amount = order.getAmount();
        // Read before the flush: saveAndFlush increments the in-memory version,
        // so taking it afterwards would give a retry a different key.
        String idempotencyKey = "order-" + id + "-v" + order.getVersion();
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(Instant.now());
        order.setCancellationReason(reason);
        Order saved = repository.saveAndFlush(order);
        if (refundable) {
            try {
                paymentClient.refund(id, amount, idempotencyKey);
            } catch (RuntimeException ex) {
                // Any failure out of the port is a refund failure: a real client
                // throws RestClientException, ResourceAccessException or an NPE on a
                // malformed response, none of which is a PaymentException. Throwing
                // here is also what rolls the transaction back, leaving the order PAID.
                throw new RefundFailedException(id, ex);
            }
        }
        return OrderResponse.from(saved);
    }

    /**
     * @param email filters by customer when provided; a null or blank value
     *              lists every order.
     */
    @Transactional(readOnly = true)
    public List<OrderResponse> findAll(String email) {
        List<Order> orders = StringUtils.hasText(email)
                ? repository.findByCustomerEmailIgnoreCase(email)
                : repository.findAll();
        return orders.stream().map(OrderResponse::from).toList();
    }
}
