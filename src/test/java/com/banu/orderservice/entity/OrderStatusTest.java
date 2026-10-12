package com.banu.orderservice.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The transition matrix needs no Spring context, so it is asserted here in
 * isolation and the slice tests are left to cover the HTTP mapping of a refusal.
 */
class OrderStatusTest {

    @ParameterizedTest
    @CsvSource({"NEW,PAID", "NEW,CANCELLED", "PAID,SHIPPED", "PAID,CANCELLED"})
    void allowsForwardMovesAndCancellationBeforeShipping(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "NEW,SHIPPED",
        "PAID,NEW",
        "SHIPPED,PAID",
        "SHIPPED,CANCELLED",
        "CANCELLED,PAID",
        "CANCELLED,NEW"
    })
    void rejectsSkippedStepsBackwardsMovesAndMovesOutOfATerminalStatus(
            OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    /**
     * No self-edges by design. The idempotent no-op for a PATCH to the current
     * status is OrderService's job, so it stays out of the allowed-transition
     * lists that refusal messages print.
     */
    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void hasNoSelfEdgeForAnyStatus(OrderStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @Test
    void terminalStatusesHaveNoAllowedTransitions() {
        assertThat(OrderStatus.SHIPPED.allowedTransitions()).isEmpty();
        assertThat(OrderStatus.CANCELLED.allowedTransitions()).isEmpty();
    }

    @Test
    void allowedTransitionsSpellOutTheOptionsForErrorBodies() {
        assertThat(OrderStatus.NEW.allowedTransitions())
                .containsExactlyInAnyOrder(OrderStatus.PAID, OrderStatus.CANCELLED);
        assertThat(OrderStatus.PAID.allowedTransitions())
                .containsExactlyInAnyOrder(OrderStatus.SHIPPED, OrderStatus.CANCELLED);
    }
}
