package com.toni.marketplace.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Throttle policy for the credential endpoints ({@code POST /api/auth/login}
 * and {@code POST /api/auth/register}).
 *
 * <p>Defaults: enabled, 5 attempts per minute per client IP. Override with
 * e.g. {@code app.auth.rate-limit.attempts-per-minute=10}. The shared test
 * profile disables the limiter ({@code src/test/resources/application.properties})
 * because the suite hammers these endpoints from a single test IP; the
 * dedicated {@code AuthRateLimitTest} re-enables it explicitly.
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
}
