package com.toni.marketplace.dispute;

/**
 * Lifecycle of an order dispute (backlog #169). A dispute starts
 * {@link #OPEN} — the order sits in {@code DISPUTED} and every lifecycle
 * transition on it is frozen — and an ADMIN resolves it exactly once:
 * {@link #RESOLVED_REFUND} (the mock refund seam ran, the order is
 * REFUNDED) or {@link #RESOLVED_RELEASE} (the dispute is dismissed, the
 * order returns to its pre-dispute status). Both resolutions are
 * terminal; see {@link DisputeService} for the idempotency rules on
 * repeat resolutions.
 */
public enum DisputeStatus {
  OPEN,
  RESOLVED_REFUND,
  RESOLVED_RELEASE
}
