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
  REPORT_APPEAL
}
