package com.toni.marketplace.item;

import java.time.Instant;

/**
 * Public view of a listing. JPA entities never leave the service layer, so
 * controllers serialize this DTO instead — no lazy associations and no
 * internal persistence fields (e.g. the optimistic-lock {@code version})
 * leak into the JSON contract.
 */
public record ItemDto(
    Long id,
    String title,
    String description,
    Long priceCents,
    ItemStatus status,
    Long sellerId,
    Long categoryId,
    String categoryName,
    Instant createdAt,
    Instant updatedAt) {
}
