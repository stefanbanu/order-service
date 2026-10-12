package com.banu.orderservice.exception;

/**
 * Like {@link OrderNotFoundException}, a plain RuntimeException so the service
 * layer stays free of HTTP concerns; ApiExceptionHandler maps this to 502.
 *
 * <p>Thrown from inside the cancellation transaction, so raising it is also what
 * rolls the cancellation back and leaves the order PAID.
 */
public class RefundFailedException extends RuntimeException {

    public RefundFailedException(Long orderId, Throwable cause) {
        super("Refund for order " + orderId + " failed; the order is unchanged", cause);
    }
}
