package com.toni.marketplace.item;

import java.time.Instant;

/**
 * One saved search as the SPA renders it (backlog #164): the discrete
 * filter set it restores, plus {@code matchCount} — how many listings
 * the browse page returns for exactly these filters right now, computed
 * on read through {@link ItemService#listItems} (see
 * {@link SavedSearchService}). There is no background matching job and
 * no stored count in v1, so the number can never go stale.
 */
public record SavedSearchDto(
    Long id,
    String keyword,
    Long categoryId,
    Long minPriceCents,
    Long maxPriceCents,
    String sort,
    long matchCount,
    Instant createdAt) {}
