package com.banu.orderservice.exception;

import com.banu.orderservice.entity.OrderStatus;
import java.util.Set;

/**
 * Like {@link OrderNotFoundException}, a plain RuntimeException so the service
 * layer stays free of HTTP concerns; ApiExceptionHandler maps this to 409.
 */
public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(Long id, OrderStatus from, OrderStatus to) {
        super(describe(id, from, to));
    }

    private static String describe(Long id, OrderStatus from, OrderStatus to) {
        Set<OrderStatus> allowed = from.allowedTransitions();
        if (allowed.isEmpty()) {
            return "Order " + id + " is " + from + ", which is final; it cannot become " + to;
        }
        return "Order " + id + " is " + from + " and cannot become " + to + "; allowed: " + allowed;
    }
}