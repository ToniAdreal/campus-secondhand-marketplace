package com.toni.marketplace.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Throttle policy for the credential endpoints ({@code POST /api/auth/login}
 * and {@code POST /api/auth/register}), for the refresh endpoint
 * ({@code POST /api/auth/refresh}), and for message sends
 * ({@code POST /api/messages}).
 *
 * <p>Defaults: enabled, 5 attempts per minute per client IP on the credential
 * endpoints, 30 per minute on the refresh endpoint (its own, more generous
 * bucket — see {@link #getRefreshAttemptsPerMinute}), and 30 sends per minute
 * per authenticated user on the message endpoint (see
 * {@link #getMessageAttemptsPerMinute}). Override with
 * e.g. {@code app.auth.rate-limit.attempts-per-minute=10}. The shared test
 * profile disables the limiter ({@code src/test/resources/application.properties})
 * because the suite hammers these endpoints from a single test IP; the
 * dedicated {@code AuthRateLimitTest} / {@code AuthRefreshRateLimitTest} /
 * {@code MessageRateLimitTest} re-enable it explicitly.
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

  /**
   * Token-bucket size and sustained refill rate per minute per
   * <em>authenticated user</em> (JWT principal id) for
   * {@code POST /api/messages}. Keyed on the user rather than the IP: the
   * endpoint is authenticated, so a dorm full of users behind one NAT
   * address must not share a single bucket. Unauthenticated/unsigned
   * requests (which the security chain will 401 anyway) fall back to a
   * per-IP bucket so anonymous hammering is still throttled. Must be
   * &ge; 1.
   */
  private int messageAttemptsPerMinute = 30;

  public int getMessageAttemptsPerMinute() {
    return messageAttemptsPerMinute;
  }

  public void setMessageAttemptsPerMinute(int messageAttemptsPerMinute) {
    this.messageAttemptsPerMinute = messageAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per client IP
   * for the password-reset endpoints (backlog #90):
   * {@code POST /api/auth/password-reset} (a mail-bombing surface) and
   * {@code POST /api/auth/password-reset/confirm} (a token-guessing
   * surface) share this one bucket. Sized like the credential bucket
   * (5/min): a legitimate user requests one reset and confirms once, so
   * the ceiling only bites automation. Must be &ge; 1.
   */
  private int passwordResetAttemptsPerMinute = 5;

  public int getPasswordResetAttemptsPerMinute() {
    return passwordResetAttemptsPerMinute;
  }

  public void setPasswordResetAttemptsPerMinute(int passwordResetAttemptsPerMinute) {
    this.passwordResetAttemptsPerMinute = passwordResetAttemptsPerMinute;
  }

  /**
   * Token-bucket size and sustained refill rate per minute per client IP
   * for the second-factor exchange {@code POST /api/auth/2fa/authenticate}
   * (backlog #101). The challenge is a 5-minute Bearer <redacted> for a
   * 6-digit code, so this endpoint is an online-guessing surface; sized
   * like the credential bucket (5/min) — a legitimate exchange is a
   * single attempt, so the ceiling only bites automation. It has its
   * own bucket: TOTP traffic must neither consume nor be starved by the
   * credential bucket (and the tests pin that a login from the same IP
   * still works when this bucket is empty). Unlike the credential
   * bucket, a successful exchange does NOT reset it — resetting on
   * success would let anyone holding one valid challenge+code refill a
   * guesser's budget. Must be &ge; 1.
   */
  private int totpAttemptsPerMinute = 5;

  public int getTotpAttemptsPerMinute() {
    return totpAttemptsPerMinute;
  }

  public void setTotpAttemptsPerMinute(int totpAttemptsPerMinute) {
    this.totpAttemptsPerMinute = totpAttemptsPerMinute;
  }
}
