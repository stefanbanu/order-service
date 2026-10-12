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
 * CANCELLED instead. SHIPPED and CANCELLED are terminal. A status cannot
 * transition to itself, so a repeated PATCH is reported rather than silently
 * accepted.
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