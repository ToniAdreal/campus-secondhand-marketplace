package com.toni.marketplace.item;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Payload for editing a listing ({@code PATCH /api/items/{id}}). All fields
 * are optional — a {@code null} field is left untouched (partial update).
 * Present fields are validated with the same rules as
 * {@link ItemCreateRequest} ({@link ItemStatus} is deliberately absent: the
 * status machine still lives on {@code PATCH /api/items/{id}/status}).
 *
 * <p>A blank-but-present title is rejected by the service layer with 400
 * (no jakarta annotation distinguishes "absent" from "blank" on a nullable
 * field).
 */
public record ItemUpdateRequest(
    @Size(max = 200, message = "title must be at most 200 characters")
    String title,

    @Size(max = 5000, message = "description must be at most 5000 characters")
    String description,

    @Positive(message = "priceCents must be positive")
    Long priceCents,

    @Positive(message = "categoryId must be positive")
    Long categoryId) {
}
