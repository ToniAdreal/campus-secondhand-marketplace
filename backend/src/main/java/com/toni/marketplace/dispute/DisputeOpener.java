package com.toni.marketplace.dispute;

/**
 * Who opened a dispute (backlog #255). Buyer-opened disputes date
 * back to backlog #169; seller-opened disputes arrived with #255 — a
 * seller whose buyer misbehaves (false non-receipt claim, abusive
 * thread) previously had no order-level escalation. The opener decides
 * who gets the open notification (the other party), and is exposed on
 * {@link DisputeDto} so the ADMIN queue and both parties can see who
 * escalated. Pre-#255 rows backfill to BUYER (Flyway V74) — every
 * dispute opened before this change was buyer-opened by construction.
 */
public enum DisputeOpener {
  BUYER,
  SELLER
}
