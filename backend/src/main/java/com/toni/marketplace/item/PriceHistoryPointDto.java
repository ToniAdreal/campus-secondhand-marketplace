package com.toni.marketplace.item;

import java.time.Instant;

/**
 * One point on a listing's price timeline (backlog #201), as returned
 * by {@code GET /api/items/{id}/price-history}.
 *
 * <p>The component set is the contract, pinned by a key-set test:
 * exactly {@code priceCents} + {@code changedAt}. No row id (the order
 * in the list is the order that matters) and no editor identity — who
 * changed a price stays in the audit log (#98), never on this public
 * read.
 */
public record PriceHistoryPointDto(Long priceCents, Instant changedAt) {}
