package com.banu.orderservice.client;

/**
 * Unchecked on purpose: a refund failure is not something a caller can
 * meaningfully recover from in place, and an unchecked type keeps
 * {@link PaymentClient#refund} free of a throws clause so a test double can
 * raise it with a plain {@code willThrow}.
 */
public class PaymentException extends RuntimeException {

    public PaymentException(String message) {
        super(message);
    }

    public PaymentException(String message, Throwable cause) {
        super(message, cause);
    }
}
