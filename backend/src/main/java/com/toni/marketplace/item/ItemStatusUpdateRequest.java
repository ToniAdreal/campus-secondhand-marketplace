package com.toni.marketplace.item;

import jakarta.validation.constraints.NotNull;

/**
 * Payload for {@code PATCH /api/items/{id}/status}. Jakarta validation is
 * enforced by the controller via {@code @Valid}: a missing status is rejected
 * with the project's 400 {@code {code,message,data}} envelope before the
 * service layer is reached.
 *
 * <p>Only {@link ItemStatus#SOLD} is a meaningful requested value — this
 * endpoint marks a listing sold, it does not move listings through an
 * arbitrary status machine. A well-formed but non-{@code SOLD} status (e.g.
 * {@code AVAILABLE}) is answered 422 by the service, and an unparseable
 * value (e.g. {@code "BOGUS"}) is answered 400 by
 * {@code GlobalExceptionHandler#handleNotReadable}.
 */
public record ItemStatusUpdateRequest(
    @NotNull(message = "status must be set")
    ItemStatus status) {
}
