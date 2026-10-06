package com.toni.marketplace.item;

/**
 * Payload for creating a listing. Jakarta validation constraints land on this
 * record when the create-listing endpoint is added; the mapper turns it into
 * an {@link Item} entity together with the authenticated seller id.
 */
public record ItemCreateRequest(
    String title,
    String description,
    Long priceCents) {
}
