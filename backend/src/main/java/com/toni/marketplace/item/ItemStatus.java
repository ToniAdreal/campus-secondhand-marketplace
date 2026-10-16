package com.toni.marketplace.item;

public enum ItemStatus {
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
