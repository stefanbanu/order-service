package com.banu.orderservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.banu.orderservice.client.PaymentClient;
import com.banu.orderservice.client.PaymentException;
import com.banu.orderservice.dto.OrderResponse;
import com.banu.orderservice.entity.Order;
import com.banu.orderservice.entity.OrderStatus;
import com.banu.orderservice.exception.InvalidStatusTransitionException;
import com.banu.orderservice.exception.OrderNotFoundException;
import com.banu.orderservice.exception.RefundFailedException;
import com.banu.orderservice.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Plain JUnit + Mockito, no Spring context: cancellation is orchestration over a
 * repository and a payment port, so the rules below are provable without a
 * database. What stays out of reach here is rollback-on-refund-failure, which
 * needs a real transaction and lives in OrderCancelIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-12T10:00:00Z");

    @Mock
    private OrderRepository repository;

    @Mock
    private PaymentClient paymentClient;

    @InjectMocks
    private OrderService service;

    private static Order order(long id, OrderStatus status, String amount) {
        Order order = new Order();
        order.setId(id);
        order.setCustomerEmail("bob@example.com");
        order.setAmount(new BigDecimal(amount));
        order.setStatus(status);
        order.setCreatedAt(CREATED_AT);
        order.setVersion(0L);
        return order;
    }

    /** saveAndFlush hands back whatever it was given, like a real flush of a managed entity. */
    private void savesWhatItIsGiven() {
        given(repository.saveAndFlush(any(Order.class))).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void cancelOfANewOrderStampsTheCancellationAndDoesNotRefund() {
        Order stored = order(7L, OrderStatus.NEW, "42.50");
        given(repository.findById(7L)).willReturn(Optional.of(stored));
        savesWhatItIsGiven();

        OrderResponse cancelled = service.cancel(7L, "changed my mind");

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancelledAt()).isNotNull();
        assertThat(cancelled.cancellationReason()).isEqualTo("changed my mind");
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stored.getCancelledAt()).isNotNull();
        assertThat(stored.getCancellationReason()).isEqualTo("changed my mind");
        verify(repository).saveAndFlush(stored);
        verifyNoInteractions(paymentClient);
    }

    @Test
    void cancelOfANewOrderAcceptsANullReasonAndLeavesItNull() {
        Order stored = order(7L, OrderStatus.NEW, "42.50");
        given(repository.findById(7L)).willReturn(Optional.of(stored));
        savesWhatItIsGiven();

        OrderResponse cancelled = service.cancel(7L, null);

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancelledAt()).isNotNull();
        assertThat(cancelled.cancellationReason()).isNull();
        assertThat(stored.getCancellationReason()).isNull();
    }

    @Test
    void cancelOfAPaidOrderRefundsItsOwnIdAndAmountExactlyOnce() {
        Order stored = order(7L, OrderStatus.PAID, "42.50");
        given(repository.findById(7L)).willReturn(Optional.of(stored));
        savesWhatItIsGiven();

        OrderResponse cancelled = service.cancel(7L, "fraud");

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(paymentClient, times(1)).refund(eq(7L), amount.capture());
        assertThat(amount.getValue()).isEqualByComparingTo("42.50");
    }

    /**
     * The anti-double-refund invariant: the flush takes the row's version lock
     * before any money moves, so a concurrent cancel fails its version check
     * rather than refunding the same order twice. Refunding first would lose that
     * ordering, which is why it is asserted here rather than left to the comment
     * in OrderService.
     */
    @Test
    void cancelOfAPaidOrderFlushesBeforeItRefunds() {
        Order stored = order(7L, OrderStatus.PAID, "42.50");
        given(repository.findById(7L)).willReturn(Optional.of(stored));
        savesWhatItIsGiven();

        service.cancel(7L, null);

        InOrder sequence = inOrder(repository, paymentClient);
        sequence.verify(repository).saveAndFlush(stored);
        sequence.verify(paymentClient).refund(eq(7L), any(BigDecimal.class));
        sequence.verifyNoMoreInteractions();
    }

    @Test
    void cancelOfAShippedOrderIsRefusedWithoutWritingOrRefunding() {
        Order stored = order(7L, OrderStatus.SHIPPED, "42.50");
        given(repository.findById(7L)).willReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.cancel(7L, "too late"))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessage("Order 7 is SHIPPED, which is final; it cannot become CANCELLED");

        assertThat(stored.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(stored.getCancelledAt()).isNull();
        assertThat(stored.getCancellationReason()).isNull();
        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).save(any());
        verifyNoInteractions(paymentClient);
    }

    @Test
    void cancelOfAnAlreadyCancelledOrderReturnsItUnchangedWithoutWritingOrRefunding() {
        Order stored = order(7L, OrderStatus.CANCELLED, "42.50");
        Instant firstCancelledAt = Instant.parse("2026-10-12T11:00:00Z");
        stored.setCancelledAt(firstCancelledAt);
        stored.setCancellationReason("the original reason");
        given(repository.findById(7L)).willReturn(Optional.of(stored));

        OrderResponse again = service.cancel(7L, "a different reason");

        assertThat(again.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(again.cancelledAt()).isEqualTo(firstCancelledAt);
        assertThat(again.cancellationReason()).isEqualTo("the original reason");
        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).save(any());
        verifyNoInteractions(paymentClient);
    }

    @Test
    void cancelOfAnUnknownIdThrowsOrderNotFoundAndNeverRefunds() {
        given(repository.findById(9999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(9999L, "whatever"))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessage("No order with id 9999");

        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(paymentClient);
    }

    @Test
    void cancelWrapsAPaymentExceptionInRefundFailedKeepingTheCause() {
        Order stored = order(7L, OrderStatus.PAID, "42.50");
        given(repository.findById(7L)).willReturn(Optional.of(stored));
        savesWhatItIsGiven();
        PaymentException provider = new PaymentException("provider unavailable");
        willThrow(provider).given(paymentClient).refund(eq(7L), any(BigDecimal.class));

        assertThatThrownBy(() -> service.cancel(7L, "fraud"))
                .isInstanceOf(RefundFailedException.class)
                .hasMessage("Refund for order 7 failed; the order is unchanged")
                .cause()
                .isSameAs(provider);

        verify(paymentClient, times(1)).refund(eq(7L), any(BigDecimal.class));
    }

    /**
     * updateStatus routes a CANCELLED target through cancel(), so the PATCH gets
     * the refund and the cancelledAt stamp instead of a bare status write. Before
     * the delegation this went through the matrix (PAID -> CANCELLED is allowed)
     * and moved no money.
     */
    @Test
    void updateStatusToCancelledDelegatesToCancelAndRefundsAPaidOrder() {
        Order stored = order(7L, OrderStatus.PAID, "42.50");
        given(repository.findById(7L)).willReturn(Optional.of(stored));
        savesWhatItIsGiven();

        OrderResponse cancelled = service.updateStatus(7L, OrderStatus.CANCELLED);

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancelledAt()).isNotNull();
        // A PATCH carries no reason, so there is none to record.
        assertThat(cancelled.cancellationReason()).isNull();
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(paymentClient, times(1)).refund(eq(7L), amount.capture());
        assertThat(amount.getValue()).isEqualByComparingTo("42.50");
        verify(repository).saveAndFlush(stored);
    }

    /**
     * The delegation runs before the same-status no-op, so the no-op now comes
     * out of cancel()'s own already-CANCELLED early return: still no write, no
     * second refund, and the reason an earlier cancel recorded is left alone.
     */
    @Test
    void updateStatusToCancelledOnAnAlreadyCancelledOrderStaysANoOp() {
        Order stored = order(7L, OrderStatus.CANCELLED, "42.50");
        Instant firstCancelledAt = Instant.parse("2026-10-12T11:00:00Z");
        stored.setCancelledAt(firstCancelledAt);
        stored.setCancellationReason("the original reason");
        given(repository.findById(7L)).willReturn(Optional.of(stored));

        OrderResponse again = service.updateStatus(7L, OrderStatus.CANCELLED);

        assertThat(again.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(again.cancelledAt()).isEqualTo(firstCancelledAt);
        assertThat(again.cancellationReason()).isEqualTo("the original reason");
        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).save(any());
        verifyNoInteractions(paymentClient);
    }

    /**
     * The refund catches RuntimeException, not PaymentException: a real client
     * throws RestClientException or ResourceAccessException, or an NPE on a
     * malformed response, and any of those still has to surface as a 502 with the
     * order left PAID rather than escape as a 500.
     */
    @Test
    void cancelWrapsANonPaymentRuntimeExceptionInRefundFailedKeepingTheCause() {
        Order stored = order(7L, OrderStatus.PAID, "42.50");
        given(repository.findById(7L)).willReturn(Optional.of(stored));
        savesWhatItIsGiven();
        IllegalStateException provider = new IllegalStateException("connect timeout");
        willThrow(provider).given(paymentClient).refund(eq(7L), any(BigDecimal.class));

        assertThatThrownBy(() -> service.cancel(7L, "fraud"))
                .isInstanceOf(RefundFailedException.class)
                .hasMessage("Refund for order 7 failed; the order is unchanged")
                .cause()
                .isSameAs(provider);

        verify(paymentClient, times(1)).refund(eq(7L), any(BigDecimal.class));
    }

    /**
     * The lost-lock path: the flush is what fails, so the refund must not have
     * been attempted. This is the single-threaded proof of the ordering that
     * stops a concurrent cancel refunding twice.
     */
    @Test
    void cancelDoesNotRefundWhenTheFlushLosesTheOptimisticLock() {
        Order stored = order(7L, OrderStatus.PAID, "42.50");
        given(repository.findById(7L)).willReturn(Optional.of(stored));
        given(repository.saveAndFlush(any(Order.class)))
                .willThrow(new OptimisticLockingFailureException("Row 7 was updated concurrently"));

        assertThatThrownBy(() -> service.cancel(7L, "fraud"))
                .isInstanceOf(OptimisticLockingFailureException.class);

        verifyNoInteractions(paymentClient);
    }
}
