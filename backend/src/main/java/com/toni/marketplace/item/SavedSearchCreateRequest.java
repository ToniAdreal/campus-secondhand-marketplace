package com.toni.marketplace.item;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Create body for a saved search (backlog #164): the canonical browse
 * filter set (#96) as discrete fields — never a serialized query string.
 * Every field is optional; null means "no filter" for that dimension.
 * The bean constraints here are only the coarse shape (non-negative
 * prices, a storable keyword); the semantic rules — min &le; max and the
 * sort allowlist — are enforced by {@link ItemService}'s own filter
 * validation in {@link SavedSearchService}, so a saved value is always
 * a value the browse page itself would accept.
 */
public record SavedSearchCreateRequest(
    @Size(max = 255) String keyword,
    Long categoryId,
    @PositiveOrZero Long minPriceCents,
    @PositiveOrZero Long maxPriceCents,
    String sort) {}
