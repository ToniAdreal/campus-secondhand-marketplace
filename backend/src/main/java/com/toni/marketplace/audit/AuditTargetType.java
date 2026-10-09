package com.toni.marketplace.audit;

/**
 * What an {@link AuditLog} row's {@code targetId} points at (backlog #98).
 * The id is the row id in the target's own table ({@code app_user} for
 * {@link #USER}, {@code orders} for {@link #ORDER}) — the audit table
 * carries no foreign keys, so a trail row stays meaningful even if the
 * target row is later deleted.
 */
public enum AuditTargetType {
  USER,
  ORDER
}
