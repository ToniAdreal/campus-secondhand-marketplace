package com.toni.marketplace.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Password-reset policy (backlog #90). The token lifetime is deliberately
 * short — a reset token is a bearer credential for a full password change,
 * so it lives minutes, not days (contrast the refresh-token family's 30 d
 * absolute lifetime). Override with
 * {@code app.auth.password-reset.token-ttl=15m}.
 */
@ConfigurationProperties(prefix = "app.auth.password-reset")
public class PasswordResetProperties {

  /** How long an issued reset token stays confirmable. Must be positive. */
  private Duration tokenTtl = Duration.ofMinutes(30);

  /**
   * Test-only reset-token capture (backlog #407, step C1 unblocking the
   * #399 Playwright password-reset journey). When true, the
   * {@link PasswordResetMailSender} bean is the capturing implementation
   * ({@link CapturingPasswordResetMailSender}) and the test-only read
   * endpoint {@code GET /api/test/auth/password-reset-token} exists, so
   * the throwaway e2e stack can read the token it could never get from
   * a mailbox (the default sender logs with the token redacted, and the
   * token never appears in any API response). When false (the default)
   * the logging sender is wired and the endpoint does not exist at all
   * (a request to the path 404s). Token rules are unchanged either way
   * (single-use, 30-min expiry, hash-only storage, identical 401s).
   *
   * <p>Enable only where the backend is a throwaway test fixture — the
   * Playwright e2e stack sets it via
   * {@code APP_AUTH_PASSWORD_RESET_E2E_TOKEN_CAPTURE_ENABLED=true} in
   * {@code frontend/playwright.config.ts}. It must never be set on a
   * real deployment: with it on, anyone who can reach the backend can
   * read a live reset token for any user id and take over the account.
   * Password-reset scope only: the email-verification (#138) and
   * pending-email-change (#208) seams stay separate per the
   * own-purpose rule.
   */
  private boolean e2eTokenCaptureEnabled = false;

  public Duration getTokenTtl() {
    return tokenTtl;
  }

  public void setTokenTtl(Duration tokenTtl) {
    this.tokenTtl = tokenTtl;
  }

  public boolean isE2eTokenCaptureEnabled() {
    return e2eTokenCaptureEnabled;
  }

  public void setE2eTokenCaptureEnabled(boolean e2eTokenCaptureEnabled) {
    this.e2eTokenCaptureEnabled = e2eTokenCaptureEnabled;
  }
}
