package com.banu.orderservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

import com.banu.orderservice.dto.OrderRequest;
import com.banu.orderservice.dto.OrderResponse;
import com.banu.orderservice.entity.OrderStatus;
import com.banu.orderservice.exception.InvalidStatusTransitionException;
import com.banu.orderservice.exception.OrderNotFoundException;
import com.banu.orderservice.service.OrderService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService service;

    private static OrderResponse response(long id, String email, String amount) {
        return response(id, email, amount, OrderStatus.NEW);
    }

    private static OrderResponse response(long id, String email, String amount, OrderStatus status) {
        return new OrderResponse(id, email, new BigDecimal(amount), status, CREATED_AT);
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
}
