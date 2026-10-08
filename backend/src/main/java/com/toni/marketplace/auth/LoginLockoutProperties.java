package com.toni.marketplace.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-account login-lockout policy for {@code POST /api/auth/login}
 * (backlog #60).
 *
 * <p>The per-IP token bucket ({@link AuthRateLimitProperties}) does not stop
 * a distributed slow brute-force against a single account (N IPs &times;
 * 5/min each), so consecutive failures are additionally tracked per
 * username on {@code app_user} (Flyway V11). After {@code maxAttempts}
 * consecutive failures the account locks for {@code lockDuration} and the
 * login endpoint answers {@code 423 Locked} with a {@code Retry-After}
 * header; a successful login resets the counter. Lockout applies to
 * existing accounts only — unknown identifiers keep getting the identical
 * 401 (no enumeration oracle).
 *
 * <p>Defaults: enabled, 5 failures, 15-minute lock. Override with e.g.
 * {@code app.auth.login-lockout.max-attempts=3}.
 */
@ConfigurationProperties(prefix = "app.auth.login-lockout")
public class LoginLockoutProperties {

  /** Master switch; when false, failed logins are never tracked per account. */
  private boolean enabled = true;

  /** Consecutive failures after which the account locks. Must be &ge; 1. */
  private int maxAttempts = 5;

  /** How long the account stays locked. Must be positive. */
  private Duration lockDuration = Duration.ofMinutes(15);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public Duration getLockDuration() {
    return lockDuration;
  }

  public void setLockDuration(Duration lockDuration) {
    this.lockDuration = lockDuration;
  }
}
