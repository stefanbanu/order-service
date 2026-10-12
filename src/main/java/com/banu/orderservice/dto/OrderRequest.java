package com.banu.orderservice.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Carries only the client-settable fields. The absence of id, status and
 * createdAt is what makes them unsettable from outside.
 */
public record OrderRequest(

        @NotBlank
        @Email
        String customerEmail,

        @NotNull
        @Positive
        @Digits(integer = 17, fraction = 2)
        BigDecimal amount) {
}
