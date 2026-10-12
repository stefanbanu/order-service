package com.banu.orderservice.dto;

import com.banu.orderservice.entity.Order;
import com.banu.orderservice.entity.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(
        Long id,
        String customerEmail,
        BigDecimal amount,
        OrderStatus status,
        Instant createdAt) {

    /**
     * The single place an entity becomes a response, so what reaches the wire is
     * decided by this record's components rather than by whatever fields Order
     * later grows. Exists as a static factory to be usable as a method
     * reference: OrderService maps both the single lookup and the list through
     * {@code OrderResponse::from}.
     */
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerEmail(),
                order.getAmount(),
                order.getStatus(),
                order.getCreatedAt());
    }
}
