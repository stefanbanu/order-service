package com.banu.orderservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banu.orderservice.client.PaymentException;
import com.banu.orderservice.dto.OrderRequest;
import com.banu.orderservice.dto.OrderResponse;
import com.banu.orderservice.entity.OrderStatus;
import com.banu.orderservice.exception.InvalidStatusTransitionException;
import com.banu.orderservice.exception.OrderNotFoundException;
import com.banu.orderservice.exception.RefundFailedException;
import com.banu.orderservice.service.OrderService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Request bodies are plain JSON literals rather than ObjectMapper output: it
 * keeps the exact payload under test visible and sidesteps Boot 4's move to
 * Jackson 3 (tools.jackson), where the old com.fasterxml ObjectMapper import
 * is no longer on the classpath.
 */
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-12T10:00:00Z");
    private static final Instant CANCELLED_AT = Instant.parse("2026-10-12T11:30:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService service;

    private static OrderResponse response(long id, String email, String amount) {
        return response(id, email, amount, OrderStatus.NEW);
    }

    /**
     * The trailing cancelledAt/cancellationReason are null for every status but
     * CANCELLED, which is what {@link #cancelled} is for. OrderResponse is
     * constructed positionally here, so appending components to the record shows
     * up as a compile error in exactly these two helpers.
     */
    private static OrderResponse response(long id, String email, String amount, OrderStatus status) {
        return new OrderResponse(id, email, new BigDecimal(amount), status, CREATED_AT, null, null);
    }

    private static OrderResponse cancelled(long id, String email, String amount, String reason) {
        return new OrderResponse(id, email, new BigDecimal(amount), OrderStatus.CANCELLED,
                CREATED_AT, CANCELLED_AT, reason);
    }

    @Test
    void createReturns201WithLocationAndBody() throws Exception {
        given(service.create(any())).willReturn(response(1L, "bob@example.com", "42.50"));

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/orders/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.customerEmail").value("bob@example.com"))
                .andExpect(jsonPath("$.amount").value(42.50))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void createPassesSubmittedFieldsToService() throws Exception {
        given(service.create(any())).willReturn(response(1L, "bob@example.com", "42.50"));

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<OrderRequest> captor = ArgumentCaptor.forClass(OrderRequest.class);
        verify(service).create(captor.capture());
        assertThat(captor.getValue().customerEmail()).isEqualTo("bob@example.com");
        assertThat(captor.getValue().amount()).isEqualByComparingTo("42.50");
    }

    @Test
    void createRejectsMalformedEmail() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerEmail\":\"not-an-email\",\"amount\":42.50}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.customerEmail").exists());
    }

    @Test
    void createRejectsBlankEmail() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerEmail\":\"   \",\"amount\":42.50}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.customerEmail").exists());
    }

    @Test
    void createRejectsNegativeAmount() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerEmail\":\"bob@example.com\",\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void createRejectsZeroAmount() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerEmail\":\"bob@example.com\",\"amount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void createRejectsMissingAmount() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerEmail\":\"bob@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void createRejectsAmountWithTooManyDecimals() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerEmail\":\"bob@example.com\",\"amount\":1.234}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void createReportsEveryInvalidFieldAtOnce() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerEmail\":\"nope\",\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.customerEmail").exists())
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void findByIdReturns200WhenPresent() throws Exception {
        given(service.findById(1L)).willReturn(response(1L, "bob@example.com", "42.50"));

        mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.customerEmail").value("bob@example.com"));
    }

    @Test
    void findByIdReturns404WhenMissing() throws Exception {
        given(service.findById(9999L)).willThrow(new OrderNotFoundException(9999L));

        mockMvc.perform(get("/orders/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"))
                .andExpect(jsonPath("$.detail").value("No order with id 9999"));
    }

    @Test
    void findAllWithoutEmailListsEverything() throws Exception {
        given(service.findAll(isNull())).willReturn(List.of(
                response(1L, "bob@example.com", "10.00"),
                response(2L, "alice@example.com", "20.00")));

        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        verify(service).findAll(isNull());
    }

    @Test
    void findAllWithEmailFiltersByCustomer() throws Exception {
        given(service.findAll(eq("bob@example.com")))
                .willReturn(List.of(response(1L, "bob@example.com", "10.00")));

        mockMvc.perform(get("/orders").param("email", "bob@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].customerEmail").value("bob@example.com"));
    }

    @Test
    void updateStatusReturns200WithTheUpdatedOrder() throws Exception {
        given(service.updateStatus(1L, OrderStatus.PAID))
                .willReturn(response(1L, "bob@example.com", "42.50", OrderStatus.PAID));

        mockMvc.perform(patch("/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("PAID"));

        verify(service).updateStatus(1L, OrderStatus.PAID);
    }

    @Test
    void updateStatusReturns404WhenMissing() throws Exception {
        given(service.updateStatus(9999L, OrderStatus.PAID))
                .willThrow(new OrderNotFoundException(9999L));

        mockMvc.perform(patch("/orders/9999/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"));
    }

    @Test
    void updateStatusReturns409WhenTransitionNotAllowed() throws Exception {
        given(service.updateStatus(1L, OrderStatus.PAID)).willThrow(
                new InvalidStatusTransitionException(1L, OrderStatus.SHIPPED, OrderStatus.PAID));

        mockMvc.perform(patch("/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Invalid status transition"))
                .andExpect(jsonPath("$.detail").value(
                        "Order 1 is SHIPPED, which is final; it cannot become PAID"));
    }

    @Test
    void updateStatusRejectsMissingStatus() throws Exception {
        mockMvc.perform(patch("/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.status").exists());
    }

    @Test
    void updateStatusRejectsUnknownStatusWithoutReachingTheService() throws Exception {
        mockMvc.perform(patch("/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Malformed request"));

        verifyNoInteractions(service);
    }

    @Test
    void cancelReturns200WhenCalledWithNoRequestBodyAtAll() throws Exception {
        given(service.cancel(eq(1L), isNull()))
                .willReturn(cancelled(1L, "bob@example.com", "42.50", null));

        mockMvc.perform(post("/orders/1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists());

        verify(service).cancel(1L, null);
    }

    @Test
    void cancelPassesTheSubmittedReasonToTheService() throws Exception {
        given(service.cancel(eq(1L), eq("changed my mind")))
                .willReturn(cancelled(1L, "bob@example.com", "42.50", "changed my mind"));

        mockMvc.perform(post("/orders/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"changed my mind\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReason").value("changed my mind"));

        verify(service).cancel(1L, "changed my mind");
    }

    @Test
    void cancelRejectsAReasonLongerThanTheColumn() throws Exception {
        String tooLong = "x".repeat(256);

        mockMvc.perform(post("/orders/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors.reason").exists());

        verifyNoInteractions(service);
    }

    @Test
    void cancelAcceptsAReasonOfExactlyTheColumnLength() throws Exception {
        String atTheLimit = "x".repeat(255);
        given(service.cancel(eq(1L), eq(atTheLimit)))
                .willReturn(cancelled(1L, "bob@example.com", "42.50", atTheLimit));

        mockMvc.perform(post("/orders/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + atTheLimit + "\"}"))
                .andExpect(status().isOk());

        verify(service).cancel(1L, atTheLimit);
    }

    @Test
    void cancelReturns404WhenMissing() throws Exception {
        given(service.cancel(eq(9999L), any())).willThrow(new OrderNotFoundException(9999L));

        mockMvc.perform(post("/orders/9999/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Order not found"))
                .andExpect(jsonPath("$.detail").value("No order with id 9999"));
    }

    @Test
    void cancelReturns409WhenTheOrderCannotBeCancelled() throws Exception {
        given(service.cancel(anyLong(), any())).willThrow(
                new InvalidStatusTransitionException(1L, OrderStatus.SHIPPED, OrderStatus.CANCELLED));

        mockMvc.perform(post("/orders/1/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Invalid status transition"))
                .andExpect(jsonPath("$.detail").value(
                        "Order 1 is SHIPPED, which is final; it cannot become CANCELLED"));
    }

    /**
     * The handler catches the common supertype ConcurrencyFailureException, so
     * both of the ways a concurrent cancel can fail have to answer the same 409:
     * the version check below, and the H2 lock timeout in the next test.
     */
    @Test
    void cancelReturns409WhenTheOptimisticLockIsLost() throws Exception {
        given(service.cancel(anyLong(), any()))
                .willThrow(new OptimisticLockingFailureException("Row was updated by another transaction"));

        mockMvc.perform(post("/orders/1/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Concurrent modification"))
                .andExpect(jsonPath("$.detail").value(
                        "The order was modified concurrently; retry the request"));
    }

    /**
     * The lock-timeout half: the winning cancel holds the row for the whole
     * refund call, so the loser's versioned UPDATE can time out and surface as a
     * pessimistic CannotAcquireLockException instead of an optimistic failure.
     * Both are ConcurrencyFailureException, which is why one handler covers them.
     */
    @Test
    void cancelReturns409WhenTheRowLockCannotBeAcquired() throws Exception {
        given(service.cancel(anyLong(), any()))
                .willThrow(new CannotAcquireLockException("Timeout trying to lock table ORDERS"));

        mockMvc.perform(post("/orders/1/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Concurrent modification"))
                .andExpect(jsonPath("$.detail").value(
                        "The order was modified concurrently; retry the request"));
    }

    /**
     * A body Jackson cannot parse never reaches @Valid, so without the
     * HttpMessageNotReadableException handler this would be a 400 with an empty
     * body. The cancel endpoint takes an optional body, which makes a truncated
     * one the easy mistake to make against it.
     */
    @Test
    void cancelReturns400ForAMalformedJsonBody() throws Exception {
        mockMvc.perform(post("/orders/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Malformed request"))
                .andExpect(jsonPath("$.detail").value(
                        "Request body is missing or not valid JSON for this endpoint"));

        verifyNoInteractions(service);
    }

    @Test
    void cancelReturns502WhenTheRefundFails() throws Exception {
        given(service.cancel(anyLong(), any())).willThrow(
                new RefundFailedException(1L, new PaymentException("provider unavailable")));

        mockMvc.perform(post("/orders/1/cancel"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.title").value("Refund failed"))
                .andExpect(jsonPath("$.detail").value(
                        "Refund for order 1 failed; the order is unchanged"));
    }
}
