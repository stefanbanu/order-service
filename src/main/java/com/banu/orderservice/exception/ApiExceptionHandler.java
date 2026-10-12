package com.banu.orderservice.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * Plain SLF4J, no Lombok (kept to the entity in this project). The provider's
     * real error only exists in the exception, and nothing about it reaches the
     * client, so a 502 or a 409 would otherwise be undiagnosable.
     */
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ProblemDetail> onOrderNotFound(OrderNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Order not found");
        return ResponseEntity.of(problem).build();
    }

    /**
     * 409 rather than 400: the body is well formed and the order exists, so what
     * failed is the order's current state, not the request.
     */
    @ExceptionHandler(InvalidStatusTransitionException.class)
    public ResponseEntity<ProblemDetail> onInvalidStatusTransition(InvalidStatusTransitionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Invalid status transition");
        return ResponseEntity.of(problem).build();
    }

    /**
     * 409 for a cancel that lost a race: the order is in a state the caller has
     * not seen. The detail is generic because the exception's own message names
     * internal classes and entity identifiers.
     *
     * <p>Handles the common supertype {@link ConcurrencyFailureException} rather
     * than {@code OptimisticLockingFailureException} alone. The losing request
     * does not always fail its @Version check: the winner holds the row lock for
     * the whole duration of the refund call, so the loser's versioned UPDATE can
     * hit H2's lock timeout first and surface as a
     * {@code CannotAcquireLockException} (a pessimistic failure). Both are the
     * same conflict, and the broad type also covers a Spring Data translated
     * {@code jakarta.persistence.OptimisticLockException}.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ProblemDetail> onConcurrentModification(ConcurrencyFailureException ex) {
        log.warn("Concurrent modification on an order", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The order was modified concurrently; retry the request");
        problem.setTitle("Concurrent modification");
        return ResponseEntity.of(problem).build();
    }

    /**
     * 502 rather than 500: this service worked, the payment provider is what
     * failed. The cancellation was rolled back, so the order is still PAID and
     * the request can be retried.
     */
    @ExceptionHandler(RefundFailedException.class)
    public ResponseEntity<ProblemDetail> onRefundFailed(RefundFailedException ex) {
        log.error("Refund failed for order", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
        problem.setTitle("Refund failed");
        return ResponseEntity.of(problem).build();
    }

    /**
     * A body Jackson cannot parse never reaches @Valid, so Spring resolves it to
     * a 400 with an empty body. An unknown enum name is the likeliest mistake
     * against /orders/{id}/status, so it gets a ProblemDetail like every other
     * error here. The detail stays generic on purpose: Jackson's own message
     * names internal classes and the rejected value, neither of which belongs in
     * a response.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> onUnreadableBody() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body is missing or not valid JSON for this endpoint");
        problem.setTitle("Malformed request");
        return ResponseEntity.of(problem).build();
    }

    /**
     * Spring already answers 400 for a failed @Valid without this handler; what
     * it adds is the per-field breakdown so clients learn which field failed.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> onValidationFailure(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);
        return ResponseEntity.of(problem).build();
    }
}
