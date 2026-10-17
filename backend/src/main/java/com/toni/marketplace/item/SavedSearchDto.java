package com.toni.marketplace.item;

import java.time.Instant;

/**
 * One saved search as the SPA renders it (backlog #164): the discrete
 * filter set it restores, plus {@code matchCount} — how many listings
 * the browse page returns for exactly these filters right now, computed
 * on read through {@link ItemService#listItems} (see
 * {@link SavedSearchService}), so the number can never go stale. The
 * background matcher's watermark (backlog #182) is deliberately NOT a
 * field here: it is internal bookkeeping, never user data.
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
