package com.toni.marketplace.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Throttle policy for the credential endpoints ({@code POST /api/auth/login}
 * and {@code POST /api/auth/register}) and for the refresh endpoint
 * ({@code POST /api/auth/refresh}).
 *
 * <p>Defaults: enabled, 5 attempts per minute per client IP on the credential
 * endpoints, 30 per minute on the refresh endpoint (its own, more generous
 * bucket — see {@link #getRefreshAttemptsPerMinute}). Override with
 * e.g. {@code app.auth.rate-limit.attempts-per-minute=10}. The shared test
 * profile disables the limiter ({@code src/test/resources/application.properties})
 * because the suite hammers these endpoints from a single test IP; the
 * dedicated {@code AuthRateLimitTest} / {@code AuthRefreshRateLimitTest}
 * re-enable it explicitly.
 */
@ConfigurationProperties(prefix = "app.auth.rate-limit")
public class AuthRateLimitProperties {

  /** Master switch; when false the filter passes every request through. */
  private boolean enabled = true;

  /**
   * Token-bucket size and sustained refill rate per minute per client IP.
   * Must be &ge; 1.
   */
  private int attemptsPerMinute = 5;

  /**
   * Token-bucket size and sustained refill rate per minute per client IP for
   * {@code POST /api/auth/refresh} — the unauthenticated token-guessing
   * surface. Higher than the credential ceiling on purpose: legitimate
   * multi-tab clients refresh routinely (a 5/min ceiling would log users out
   * on burst tab restores), so the limit exists only to stop sustained
   * brute force, not to gate normal use. Must be &ge; 1.
   */
  private int refreshAttemptsPerMinute = 30;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getAttemptsPerMinute() {
    return attemptsPerMinute;
  }

  public void setAttemptsPerMinute(int attemptsPerMinute) {
    this.attemptsPerMinute = attemptsPerMinute;
  }

  public int getRefreshAttemptsPerMinute() {
    return refreshAttemptsPerMinute;
  }

  public void setRefreshAttemptsPerMinute(int refreshAttemptsPerMinute) {
    this.refreshAttemptsPerMinute = refreshAttemptsPerMinute;
  }
}
