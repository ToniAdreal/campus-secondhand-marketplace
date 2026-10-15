package com.toni.marketplace.offer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of POST /api/items/{id}/offers (backlog #168). The offered price
 * is in minor units (cents), mirroring the listing's priceCents; it must
 * be present and positive — a zero/negative "offer" is a validation
 * failure (400), never a stored row. Offering at or above the asking
 * price is allowed (it is still a valid purchase intent; the buyer who
 * wants certainty can use buy-now instead).
 */
public record OfferCreateRequest(
    @NotNull @Positive Long priceCents) {
}
