package com.toni.marketplace.moderation;

/**
 * Moderation state of a {@link ListingReport} (backlog #160). A report
 * starts {@link #OPEN}; an ADMIN resolves it exactly once, to
 * {@link #DISMISSED} (no action taken) or {@link #ACTIONED} (the listing
 * was withdrawn). Both are terminal — see {@link ListingReportService}
 * for the idempotency rules on repeat transitions.
 *
 * <p>Honest scope: there is deliberately no auto-hide state — a listing
 * stays visible until a human ADMIN acts on a report, however many
 * reports it collects (no auto-hide on N reports in v1).
 */
public enum ReportStatus {
  OPEN,
  DISMISSED,
  ACTIONED
}
