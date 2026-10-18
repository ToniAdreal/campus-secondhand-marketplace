package com.toni.marketplace.moderation;

/**
 * Lifecycle of a report appeal (backlog #190). An appeal starts
 * {@link #OPEN} — the seller has asked an ADMIN to reconsider an
 * ACTIONED report — and an ADMIN resolves it exactly once:
 * {@link #UPHELD} (the appeal succeeds; the listing is restored to
 * AVAILABLE when it is still WITHDRAWN) or {@link #REJECTED} (the
 * moderation decision stands). Both resolutions are terminal; see
 * {@link ReportAppealService} for the idempotency rules on repeat
 * resolutions.
 */
public enum ReportAppealStatus {
  OPEN,
  UPHELD,
  REJECTED
}
