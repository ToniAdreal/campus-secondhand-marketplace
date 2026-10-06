package com.toni.marketplace.item;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Payload for creating a listing. Jakarta validation is enforced by the
 * controller via {@code @Valid}: a blank title or a non-positive price is
 * rejected with the project's 400 {@code {code,message,data}} envelope
 * before the service layer is reached.
 */
public record ItemCreateRequest(
    @NotBlank(message = "title must not be blank")
    @Size(max = 200, message = "title must be at most 200 characters")
    String title,

    @Size(max = 5000, message = "description must be at most 5000 characters")
    String description,

    @NotNull(message = "priceCents must be set")
    @Positive(message = "priceCents must be positive")
    Long priceCents) {
}
