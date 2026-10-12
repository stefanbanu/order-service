package com.banu.orderservice.dto;

import jakarta.validation.constraints.Size;

/**
 * Body of POST /orders/{id}/cancel, itself optional. No @NotBlank on the
 * reason: a client may cancel without explaining why, and the controller
 * accepts a missing body at all, so the only rule is the 255-character limit the
 * cancellationReason column holds.
 */
public record CancelOrderRequest(

        @Size(max = 255)
        String reason) {
}
