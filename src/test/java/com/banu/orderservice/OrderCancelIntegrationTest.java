package com.banu.orderservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        verify(paymentClient, times(1)).refund(eq(Long.parseLong(id)), amount.capture());
        return amount.getValue();
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

        verify(paymentClient, times(1)).refund(eq(Long.parseLong(id)), any(BigDecimal.class));
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
                .given(paymentClient).refund(any(Long.class), any(BigDecimal.class));

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
