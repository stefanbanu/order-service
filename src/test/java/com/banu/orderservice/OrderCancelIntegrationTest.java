package com.banu.orderservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banu.orderservice.client.PaymentClient;
import com.banu.orderservice.client.PaymentException;
import com.banu.orderservice.entity.Order;
import com.banu.orderservice.entity.OrderStatus;
import com.banu.orderservice.repository.OrderRepository;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancellation end to end: controller -> service -> Hibernate -> H2, with only
 * the payment provider faked.
 *
 * <p>Deliberately a second @SpringBootTest class rather than more methods on
 * OrderApiIntegrationTest: the @MockitoBean below changes the context cache key,
 * so putting it there would rebuild that context with a mocked PaymentClient and
 * every existing test would run against a different wiring than it does today.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrderCancelIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository repository;

    @MockitoBean
    private PaymentClient paymentClient;

    /**
     * A no-op for the transactional tests, which roll back anyway. It exists for
     * the rollback test below, which has to commit and so leaves rows behind.
     */
    @AfterEach
    void clearCommittedRows() {
        repository.deleteAll();
    }

    private String createOrder(String body) throws Exception {
        String json = mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        // Typed local, not String.valueOf(JsonPath.read(...)): read() is generic,
        // so T would infer as char[] to match a String.valueOf overload and blow up.
        Number id = JsonPath.read(json, "$.id");
        return id.toString();
    }

    private ResultActions patchStatus(String id, String status) throws Exception {
        return mockMvc.perform(patch("/orders/" + id + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"));
    }

    private ResultActions cancel(String id) throws Exception {
        return mockMvc.perform(post("/orders/" + id + "/cancel"));
    }

    private ResultActions cancel(String id, String reason) throws Exception {
        return mockMvc.perform(post("/orders/" + id + "/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}"));
    }

    /** Captures the refunded amount so it can be compared by value, not by scale. */
    private BigDecimal refundedAmountFor(String id) {
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(paymentClient, times(1)).refund(eq(Long.parseLong(id)), amount.capture(), anyString());
        return amount.getValue();
    }

    /** The idempotency key of the single refund, captured so its value can be asserted. */
    private String refundKeyFor(String id) {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(paymentClient, times(1))
                .refund(eq(Long.parseLong(id)), any(BigDecimal.class), key.capture());
        return key.getValue();
    }

    /** Every key the port was handed, in call order. */
    private List<String> refundKeys(String id, int expectedCalls) {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(paymentClient, times(expectedCalls))
                .refund(eq(Long.parseLong(id)), any(BigDecimal.class), keys.capture());
        return keys.getAllValues();
    }

    private Order stored(String id) {
        return repository.findById(Long.parseLong(id)).orElseThrow();
    }

    @Test
    void cancelsANewOrderWithoutARequestBodyAndWithoutRefunding() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");

        cancel(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Integer.parseInt(id)))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists())
                .andExpect(jsonPath("$.cancellationReason").isEmpty());

        mockMvc.perform(get("/orders/" + id))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists());

        // A NEW order was never paid, so there is nothing to refund.
        verifyNoInteractions(paymentClient);
    }

    @Test
    void cancelsAPaidOrderAndRefundsItOnce() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");
        patchStatus(id, "PAID").andExpect(status().isOk());

        cancel(id, "changed my mind")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists())
                .andExpect(jsonPath("$.cancellationReason").value("changed my mind"));

        // eq(new BigDecimal("42.50")) would be scale-sensitive: BigDecimal.equals
        // treats 42.5 and 42.50 as different, so compare by value instead.
        assertThat(refundedAmountFor(id)).isEqualByComparingTo("42.50");
        // v0, not v1: inside the class-level test transaction the PATCH to PAID is
        // a managed-entity write that is never flushed on its own, so the version
        // is still the one the INSERT assigned when the cancel reads it. The
        // cancel's own flush then writes version 1 — after the key was taken.
        assertThat(refundKeyFor(id)).isEqualTo("order-" + id + "-v0");
    }

    @Test
    void aSecondCancelIsAnIdempotent200ThatDoesNotRefundAgain() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");
        patchStatus(id, "PAID").andExpect(status().isOk());
        cancel(id, "first attempt").andExpect(status().isOk());

        cancel(id, "retry after a timeout")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReason").value("first attempt"));

        // The committed cancel short-circuits the retry on the already-CANCELLED
        // early return, so the port is never reached a second time at all — the
        // key only has to dedupe retries of an attempt that rolled back.
        verify(paymentClient, times(1))
                .refund(eq(Long.parseLong(id)), any(BigDecimal.class), eq("order-" + id + "-v0"));
    }

    @Test
    void refusesToCancelAShippedOrderAndLeavesItShipped() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");
        patchStatus(id, "PAID").andExpect(status().isOk());
        patchStatus(id, "SHIPPED").andExpect(status().isOk());

        cancel(id, "too late")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Invalid status transition"))
                .andExpect(jsonPath("$.detail").value(
                        "Order " + id + " is SHIPPED, which is final; it cannot become CANCELLED"));

        mockMvc.perform(get("/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.cancelledAt").isEmpty())
                .andExpect(jsonPath("$.cancellationReason").isEmpty());

        verifyNoInteractions(paymentClient);
    }

    /**
     * The most important test here: a refund that fails must leave the order
     * PAID, which only a real rollback delivers.
     *
     * <p>Runs with the test transaction switched off (NOT_SUPPORTED) on purpose.
     * Inside the shared test transaction the service would merely join it, so
     * throwing would only mark that transaction rollback-only — a participating
     * transaction does not itself roll back — and the flushed cancellation would
     * still sit in the same persistence context. The follow-up GET would then
     * read CANCELLED straight out of the identity map and the assertion would
     * fail: it would be decided by the shared persistence context rather than by
     * rollback. Without the annotation, each request commits (or rolls back) for
     * real and the GET goes to the database; @AfterEach cleans up the committed
     * rows.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aFailedRefundRollsTheCancellationBackAndLeavesTheOrderPaid() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");
        patchStatus(id, "PAID").andExpect(status().isOk());
        willThrow(new PaymentException("provider unavailable"))
                .given(paymentClient).refund(any(Long.class), any(BigDecimal.class), anyString());

        cancel(id, "changed my mind")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.title").value("Refund failed"))
                .andExpect(jsonPath("$.detail").value(
                        "Refund for order " + id + " failed; the order is unchanged"));

        mockMvc.perform(get("/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.cancelledAt").isEmpty())
                .andExpect(jsonPath("$.cancellationReason").isEmpty());
    }

    /**
     * What the idempotency key exists for: a sequential retry after a 502.
     *
     * <p>The provider may well have processed the first refund and simply failed
     * to answer, so the rollback that leaves the order PAID is only half the
     * safety — the retry refunds again, and nothing on this side can tell it not
     * to. The protection is that both attempts carry the <em>same</em> key: the
     * rolled-back attempt left the row at the version it was loaded at, so the
     * retry reloads that version and reproduces the key, and the provider folds
     * the two calls into one refund.
     *
     * <p>NOT_SUPPORTED for the same reason as its siblings: inside the shared test
     * transaction the service would only mark it rollback-only, the flushed
     * cancellation would stay in the persistence context, and the second attempt
     * would read CANCELLED out of the identity map instead of reloading the
     * still-PAID row — which is exactly the reload this test is about.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aRetryAfterAFailedRefundSendsTheSameIdempotencyKey() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");
        patchStatus(id, "PAID").andExpect(status().isOk());
        willThrow(new PaymentException("response timed out"))
                .given(paymentClient).refund(any(Long.class), any(BigDecimal.class), anyString());

        cancel(id, "changed my mind")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.detail").value(
                        "Refund for order " + id + " failed; the order is unchanged"));

        cancel(id, "changed my mind")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.detail").value(
                        "Refund for order " + id + " failed; the order is unchanged"));

        List<String> keys = refundKeys(id, 2);
        assertThat(keys.get(1))
                .as("the retry must reproduce the first attempt's key so the provider dedupes it")
                .isEqualTo(keys.get(0));
        // The rollback left the row at the version the first attempt loaded, so
        // that version — not a post-flush or per-attempt one — is in both keys.
        assertThat(keys).containsExactly("order-" + id + "-v1", "order-" + id + "-v1");

        mockMvc.perform(get("/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.cancelledAt").isEmpty())
                .andExpect(jsonPath("$.cancellationReason").isEmpty());
    }

    /**
     * The same rollback through the PATCH alias, which is the path the delegation
     * made money-moving: a PATCH to CANCELLED now routes through cancel(), so it
     * can refund and therefore can 502. Covering only POST /cancel would leave the
     * alias free to regress into committing a cancellation it never refunded.
     *
     * <p>NOT_SUPPORTED for the same reason as its POST sibling: inside the
     * class-level test transaction the service merely joins it, so throwing only
     * marks that transaction rollback-only and the flushed cancellation stays in
     * the shared persistence context. The follow-up GET would then read CANCELLED
     * out of the identity map and the assertion would be decided by that map
     * rather than by rollback. With the test transaction off, each request commits
     * or rolls back for real and @AfterEach removes the committed rows.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aFailedRefundOnAPatchToCancelledRollsBackAndLeavesTheOrderPaid() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");
        patchStatus(id, "PAID").andExpect(status().isOk());
        willThrow(new PaymentException("provider unavailable"))
                .given(paymentClient).refund(any(Long.class), any(BigDecimal.class), anyString());

        patchStatus(id, "CANCELLED")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.title").value("Refund failed"))
                .andExpect(jsonPath("$.detail").value(
                        "Refund for order " + id + " failed; the order is unchanged"));

        mockMvc.perform(get("/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.cancelledAt").isEmpty())
                .andExpect(jsonPath("$.cancellationReason").isEmpty());
    }

    /**
     * The regression test for the delegation: before OrderService.updateStatus
     * routed CANCELLED through cancel(), this PATCH moved a PAID order to
     * CANCELLED with no refund and no cancelledAt, and the second attempt hit the
     * idempotent return — so the refund was unreachable for good.
     */
    @Test
    void patchToCancelledOnAPaidOrderRefundsItOnceAndStampsCancelledAt() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");
        patchStatus(id, "PAID").andExpect(status().isOk());

        patchStatus(id, "CANCELLED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists());

        mockMvc.perform(get("/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists());
        assertThat(refundedAmountFor(id)).isEqualByComparingTo("42.50");
        // The alias delegates to cancel(), so it carries a key of the same shape.
        // v0 for the same reason as its POST sibling: the unflushed PATCH to PAID
        // leaves the version at the one the INSERT assigned.
        assertThat(refundKeyFor(id)).isEqualTo("order-" + id + "-v0");
    }

    /**
     * The same delegation on a NEW order: nothing was paid, so nothing is
     * refunded, and a PATCH carries no reason — only POST /cancel can set one.
     */
    @Test
    void patchToCancelledOnANewOrderStampsCancelledAtWithoutARefundOrAReason() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");

        patchStatus(id, "CANCELLED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists())
                .andExpect(jsonPath("$.cancellationReason").isEmpty());

        assertThat(stored(id).getCancelledAt()).isNotNull();
        assertThat(stored(id).getCancellationReason()).isNull();
        verifyNoInteractions(paymentClient);
    }

    /**
     * The delegation must not cost the idempotent 200 the self-patch has always
     * answered: the repeat refunds nothing further and leaves the first
     * cancelledAt in place.
     */
    @Test
    void aSecondPatchToCancelledIsAnIdempotent200ThatDoesNotRefundAgain() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");
        patchStatus(id, "PAID").andExpect(status().isOk());
        String first = patchStatus(id, "CANCELLED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledAt").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Object firstCancelledAt = JsonPath.read(first, "$.cancelledAt");

        patchStatus(id, "CANCELLED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").value(firstCancelledAt));

        assertThat(refundedAmountFor(id)).isEqualByComparingTo("42.50");
    }

    /**
     * A PATCH to CANCELLED passes a null reason, so an order already cancelled
     * through POST /cancel must keep the reason it was given rather than have it
     * overwritten with null.
     */
    @Test
    void patchToCancelledAfterACancelKeepsTheReasonTheCancelSet() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");
        patchStatus(id, "PAID").andExpect(status().isOk());
        cancel(id, "changed my mind").andExpect(status().isOk());

        patchStatus(id, "CANCELLED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReason").value("changed my mind"));

        mockMvc.perform(get("/orders/" + id))
                .andExpect(jsonPath("$.cancellationReason").value("changed my mind"));
        assertThat(refundedAmountFor(id)).isEqualByComparingTo("42.50");
    }

    /**
     * The trust boundary again, this time on the cancel body: CancelOrderRequest
     * has only a reason, so cancelledAt and status are not settable from the
     * wire. Jackson ignores the unknown properties and the server-set values win
     * — asserted against the stored row, where a smuggled 2020 timestamp would
     * show up.
     */
    @Test
    void cancelIgnoresAClientSuppliedCancelledAtAndStatus() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");
        Instant beforeTheRequest = Instant.now();

        mockMvc.perform(post("/orders/" + id + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\",\"cancelledAt\":\"2020-01-01T00:00:00Z\","
                                + "\"status\":\"SHIPPED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReason").value("x"))
                .andExpect(jsonPath("$.cancelledAt").exists());

        Order row = stored(id);
        assertThat(row.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(row.getCancellationReason()).isEqualTo("x");
        assertThat(row.getCancelledAt())
                .isAfterOrEqualTo(beforeTheRequest)
                .isNotEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Test
    void returns404WhenCancellingAnUnknownId() throws Exception {
        cancel("9999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Order not found"))
                .andExpect(jsonPath("$.detail").value("No order with id 9999"));

        verifyNoInteractions(paymentClient);
    }

    @Test
    void rejects400ForAReasonLongerThanTheColumnAndLeavesTheOrderAlone() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");

        cancel(id, "x".repeat(256))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors.reason").exists());

        mockMvc.perform(get("/orders/" + id))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.cancelledAt").isEmpty());

        verifyNoInteractions(paymentClient);
    }
}
