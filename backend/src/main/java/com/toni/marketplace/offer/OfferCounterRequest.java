package com.toni.marketplace.offer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of POST /api/offers/{id}/counter (backlog #193). The seller's
 * counter price is in minor units (cents), mirroring the offer and
 * listing prices; it must be present and positive — a zero/negative
 * "counter" is a validation failure (400), never a stored value. The
 * negotiation bounds (strictly above the buyer's offer, strictly below
 * the listing's asking price) are state-dependent, so they live in
 * {@link OfferService} and answer 422, not here.
 */
public record OfferCounterRequest(
    @NotNull @Positive Long priceCents) {
}
