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
 * still replay the stored order). Override with e.g.
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
}
