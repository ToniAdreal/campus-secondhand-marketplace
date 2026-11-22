package com.toni.marketplace.audit;

/**
 * What an {@link AuditLog} row's {@code targetId} points at (backlog #98).
 * The id is the row id in the target's own table ({@code app_user} for
 * {@link #USER}, {@code orders} for {@link #ORDER}, {@code category} for
 * {@link #CATEGORY}) — the audit table carries no foreign keys, so a
 * trail row stays meaningful even if the target row is later deleted
 * (a {@code CATEGORY_DELETED} row outliving its category is the point).
 */
public enum AuditTargetType {
  USER,
  ORDER,
  CATEGORY,
  /** A {@code webhook_event} ledger row (ADMIN replay, backlog #170). */
  WEBHOOK_EVENT,
  /** A {@code report_appeal} row (seller appeal of an ACTIONED report, backlog #190). */
  REPORT_APPEAL,
  /** A {@code price_offer} row (offer-lifecycle audit, backlog #389). */
  OFFER,
  /** A {@code seller_review} row (review audit, backlog #390). */
  REVIEW,
  /** A {@code seller_payout} row (ADMIN mark-paid audit, backlog #390). */
  PAYOUT,
  /** A {@code listing_report} row (moderation report resolution, backlog #397). */
  REPORT,
  /** An {@code item} row (listing hidden by a moderation action, backlog #397). */
  ITEM,
  /** A {@code dispute_evidence} row (evidence upload/delete audit, backlog #413). */
  DISPUTE_EVIDENCE
}
