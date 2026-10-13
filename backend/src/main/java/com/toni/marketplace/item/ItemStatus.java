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
  WITHDRAWN
}
