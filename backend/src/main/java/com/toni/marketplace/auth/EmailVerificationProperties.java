package com.toni.marketplace.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Email-verification policy (backlog #138). Registration creates the
 * account unverified and issues NO session until the address is confirmed
 * through a single-use token; login of an unverified account answers 403.
 *
 * <p>The token lifetime is a day, not the password-reset flow's 30
 * minutes: a reset token is a Bearer <redacted> for a full password change
 * demanded by an already-locked-out user, while a verification link goes
 * to a mailbox the new user may only check hours later — and confirming
 * it changes nothing but a flag (it grants no session by itself). Override
 * with {@code app.auth.email-verification.token-ttl=12h}.
 *
 * <p>{@code enabled} is the master switch. It defaults to true; the shared
 * test profile turns it off ({@code src/test/resources/application.properties})
 * because the suite registers hundreds of throwaway accounts with no
 * mailbox to confirm from — the dedicated {@code EmailVerificationTest}
 * re-enables it explicitly, mirroring the rate-limiter arrangement.
 */
@ConfigurationProperties(prefix = "app.auth.email-verification")
public class EmailVerificationProperties {

  /** Master switch: when false, registration issues a session as before. */
  private boolean enabled = true;

  /** How long an issued verification token stays confirmable. Positive. */
  private Duration tokenTtl = Duration.ofHours(24);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Duration getTokenTtl() {
    return tokenTtl;
  }

  public void setTokenTtl(Duration tokenTtl) {
    this.tokenTtl = tokenTtl;
  }
}
