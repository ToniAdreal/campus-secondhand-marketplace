package com.toni.marketplace.job;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retention policy for the nightly stale-data purge
 * ({@link DataRetentionService}).
 *
 * <p>Defaults: revoked-or-expired {@code refresh_token} rows are kept
 * 30 days after creation (audit window plus rotation-chain visibility for a
 * concurrent-refresh grace period), terminal {@code idempotency_key} rows
 * are kept 90 days after their last state transition (late client retries
 * still replay the stored order), and used-or-expired
 * {@code password_reset_token} rows are kept 30 days after creation
 * (security-audit evidence, briefly — not forever), and {@code audit_log}
 * rows are kept 365 days after creation (backlog #118 — the audit trail
 * is the security record, so its window is materially longer than the
 * token windows). Override with e.g.
 * {@code app.cleanup.refresh-token-retention=7}.
 */
@ConfigurationProperties(prefix = "app.cleanup")
public class CleanupProperties {

  /**
   * Days a revoked-or-expired {@code refresh_token} row is kept after its
   * creation before the purge may delete it. Must be &ge; 1.
   */
  private long refreshTokenRetention = 30;

  /**
   * Days a terminal ({@code COMPLETED}/{@code FAILED})
   * {@code idempotency_key} row is kept after its last state transition
   * before the purge may delete it. Must be &ge; 1. {@code IN_PROGRESS}
   * rows are never purged regardless of age.
   */
  private long idempotencyRetention = 90;

  /**
   * Days a used-or-expired {@code password_reset_token} row is kept after
   * its creation before the purge may delete it. Must be &ge; 1. A
   * still-valid unused token is never purged regardless of age — the purge
   * must not kill an in-flight reset.
   */
  private long passwordResetRetention = 30;

  /**
   * Days an {@code audit_log} row is kept after its creation before the
   * purge may delete it. Must be &ge; 1. Deletion is by {@code created_at}
   * only — never filtered by action or actor, so no specific event can be
   * selectively erased; within the window the trail stays append-only
   * (backlog #118).
   */
  private long auditLogRetention = 365;

  public long getRefreshTokenRetention() {
    return refreshTokenRetention;
  }

  public void setRefreshTokenRetention(long refreshTokenRetention) {
    this.refreshTokenRetention = refreshTokenRetention;
  }

  public long getIdempotencyRetention() {
    return idempotencyRetention;
  }

  public void setIdempotencyRetention(long idempotencyRetention) {
    this.idempotencyRetention = idempotencyRetention;
  }

  public long getPasswordResetRetention() {
    return passwordResetRetention;
  }

  public void setPasswordResetRetention(long passwordResetRetention) {
    this.passwordResetRetention = passwordResetRetention;
  }

  public long getAuditLogRetention() {
    return auditLogRetention;
  }

  public void setAuditLogRetention(long auditLogRetention) {
    this.auditLogRetention = auditLogRetention;
  }
}
