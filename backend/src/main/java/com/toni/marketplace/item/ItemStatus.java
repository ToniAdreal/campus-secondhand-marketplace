package com.toni.marketplace.item;

public enum ItemStatus {
  /**
   * Seller-private draft (backlog #198): created via
   * {@code POST /api/items} with {@code status=DRAFT} (or moved there by a
   * PATCH edit), visible only to its seller (and ADMINs on the detail
   * read) — public browse/search/detail reads exclude it and another
   * user's detail read answers 404, never 403. A draft carries no
   * expiry deadline (the #177 clock starts at publish, not at create)
   * and is never orderable (order/offer creation requires
   * {@link #AVAILABLE}). {@code POST /api/items/{id}/publish} is the
   * DRAFT \u2192 AVAILABLE transition. No Flyway migration is needed:
   * the {@code item.status} column is a plain {@code VARCHAR(16)} with
   * no CHECK constraint (V1), so the new enum name persists as-is.
   */
  DRAFT,
  AVAILABLE,
  RESERVED,
  SOLD,
  /**
   * Terminal state for a listing whose seller closed their account
   * (backlog #158): withdrawn from sale, never orderable again
   * (order creation requires {@link #AVAILABLE}), and deliberately
   * distinct from {@link #SOLD} — no sale happened. The row itself is
   * kept so order history that references it (e.g. a cancelled order
   * whose listing had flipped back to AVAILABLE) stays intact.
   */
  WITHDRAWN,
  /**
   * Terminal state for a listing whose lifetime ran out (backlog #177):
   * an AVAILABLE listing past its {@code expiresAt} is treated as gone by
   * the public reads immediately (lazy-on-read) and is flipped to this
   * stored status by the nightly {@code ListingExpiryService} so reports
   * and the seller view can name the state. Renewing
   * ({@code POST /api/items/{id}/renew}) is the only way back to
   * {@link #AVAILABLE}. Distinct from {@link #WITHDRAWN} (seller closed
   * their account) and {@link #SOLD} (a sale happened).
   */
  EXPIRED
}
