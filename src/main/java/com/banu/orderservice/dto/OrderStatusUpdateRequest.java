package com.banu.orderservice.dto;

import com.banu.orderservice.entity.OrderStatus;
import jakarta.validation.constraints.NotNull;

/**
 * A one-field object rather than a bare enum string in the body, so the payload
 * can later carry a reason or an actor without breaking clients. @NotNull is
 * what turns an empty {@code {}} into a 400 instead of a null status reaching
 * the service.
 */
public record OrderStatusUpdateRequest(

        @NotNull
        OrderStatus status) {
}