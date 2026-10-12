package com.banu.orderservice.entity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Carries its own transition rules so the service layer stays a thin
 * orchestrator and the matrix is testable without a Spring context.
 *
 * <p>{@code NEW -> PAID -> SHIPPED} is the happy path, and NEW or PAID may be
 * CANCELLED instead. SHIPPED and CANCELLED are terminal.
 *
 * <p>There are deliberately no self-edges: a status is not a transition to
 * itself. OrderService treats that case as an idempotent no-op before it
 * consults this matrix, which keeps the current status out of the "allowed"
 * list that refusal messages print.
 */
public enum OrderStatus {
    NEW,
    PAID,
    SHIPPED,
    CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            NEW, EnumSet.of(PAID, CANCELLED),
            PAID, EnumSet.of(SHIPPED, CANCELLED),
            SHIPPED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    public boolean canTransitionTo(OrderStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /** Empty for a terminal status; used to spell out the options in error bodies. */
    public Set<OrderStatus> allowedTransitions() {
        return Collections.unmodifiableSet(ALLOWED.get(this));
    }
}