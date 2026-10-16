package com.toni.marketplace.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/orders/{id}/review (backlog #175). The rating is
 * the review: 1–5, required. The text is optional (a star-only review
 * is a complete review) and length-capped here at the boundary (400 on
 * violation) to match the VARCHAR(500) column; blank text is
 * normalised to null by the service.
 */
public record ReviewCreateRequest(
    @NotNull @Min(1) @Max(5) Integer rating,
    @Size(max = 500) String body) {
}
