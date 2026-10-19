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
 * (security-audit evidence, briefly — not forever), {@code audit_log}
 * rows are kept 365 days after creation (backlog #118 — the audit trail
 * is the security record, so its window is materially longer than the
 * token windows), {@code webhook_event} rows are kept 180 days after
 * creation (backlog #152 — delivery evidence for PSP reconciliation,
 * longer than the token windows, shorter than the audit trail),
 * used-or-expired {@code email_verification_token} rows are kept 30
 * days after creation (backlog #152 — aligned with the password-reset
 * window; the two token tables are siblings), {@code login_event}
 * rows are kept 180 days after creation (backlog #171 — sign-in
 * evidence aligned with the webhook window), and
 * {@code recently_viewed} trail rows are kept 30 days after the last
 * view (backlog #195 — a browsing trail is short-lived by nature, and
 * a repeat view refreshes its row, so the window runs from
 * {@code viewed_at}, not from a separate creation time). Override
 * with e.g. {@code app.cleanup.refresh-token-retention=7}.
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

  /**
   * Days a {@code webhook_event} row is kept after its creation before
   * the purge may delete it. Must be &ge; 1. Deletion is by
   * {@code created_at} only — never filtered by outcome, so no specific
   * delivery can be selectively erased (backlog #152).
   */
  private long webhookEventRetention = 180;

  /**
   * Days a used-or-expired {@code email_verification_token} row is kept
   * after its creation before the purge may delete it. Must be &ge; 1.
   * A still-valid unused token is never purged regardless of age — the
   * purge must not kill an in-flight verification. Selection is by the
   * time columns ({@code created_at} / {@code expires_at} /
   * {@code used_at}) only — never by user or address (backlog #152).
   */
  private long emailVerificationTokenRetention = 30;

  /**
   * Days a {@code login_event} row is kept after its creation before
   * the purge may delete it. Must be &ge; 1. Deletion is by
   * {@code created_at} only — never filtered by outcome or user, so no
   * specific sign-in attempt can be selectively erased (backlog #171).
   */
  private long loginEventRetention = 180;

  /**
   * Days a {@code recently_viewed} trail row is kept after its last
   * view before the purge may delete it. Must be &ge; 1. Deletion is
   * by {@code viewed_at} only — never filtered by user or listing, so
   * no specific person's trail can be selectively erased (backlog
   * #195).
   */
  private long recentlyViewedRetention = 30;

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

  public long getWebhookEventRetention() {
    return webhookEventRetention;
  }

  public void setWebhookEventRetention(long webhookEventRetention) {
    this.webhookEventRetention = webhookEventRetention;
  }

  public long getEmailVerificationTokenRetention() {
    return emailVerificationTokenRetention;
  }

  public void setEmailVerificationTokenRetention(long emailVerificationTokenRetention) {
    this.emailVerificationTokenRetention = emailVerificationTokenRetention;
  }

  public long getLoginEventRetention() {
    return loginEventRetention;
  }

  public void setLoginEventRetention(long loginEventRetention) {
    this.loginEventRetention = loginEventRetention;
  }

  public long getRecentlyViewedRetention() {
    return recentlyViewedRetention;
  }

  public void setRecentlyViewedRetention(long recentlyViewedRetention) {
    this.recentlyViewedRetention = recentlyViewedRetention;
  }
}
