package com.banu.orderservice.exception;

/**
 * Deliberately not a ResponseStatusException: the service layer stays free of
 * HTTP concerns, and ApiExceptionHandler maps this to 404 centrally.
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(Long id) {
        super("No order with id " + id);
    }
}
