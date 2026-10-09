package com.toni.marketplace.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-account lockout policy for the second-factor exchange
 * {@code POST /api/auth/2fa/authenticate} (backlog #101).
 *
 * <p>The challenge token is a 5-minute Bearer <redacted> for a 6-digit code
 * (10^6 possibilities, recovery codes aside), so a stolen or leaked
 * challenge is an online-guessing surface. Consecutive failed exchanges
 * are tracked per account on {@code app_user} (Flyway V23): after
 * {@code maxAttempts} consecutive failures the <em>authenticate
 * exchange</em> locks for {@code lockDuration} and answers {@code 423
 * Locked} with a {@code Retry-After} header — even for a correct code
 * presented with a fresh challenge. A successful exchange resets the
 * counter; an expired lock resets it on the next attempt.
 *
 * <p>Deliberate separation from the password lockout (#60, {@link
 * LoginLockoutProperties}): the TOTP counter and lock live in their own
 * columns and are consulted only by {@code authenticateTotp}. A
 * challenge holder (who by definition already passed the password
 * stage — or stole a challenge) must not be able to lock the owner out
 * of password login entirely: while TOTP-locked, {@code POST
 * /api/auth/login} still verifies the password and issues a fresh
 * challenge (202) exactly as before; only the exchange of that
 * challenge is refused until the window passes. Conversely, a password
 * lockout does not extend or clear a TOTP lock.
 *
 * <p>Defaults mirror the login lockout: enabled, 5 failures, 15-minute
 * lock. Override with e.g. {@code app.auth.totp-lockout.max-attempts=3}.
 */
@ConfigurationProperties(prefix = "app.auth.totp-lockout")
public class TotpLockoutProperties {

  /** Master switch; when false, failed exchanges are never tracked per account. */
  private boolean enabled = true;

  /** Consecutive failed exchanges after which the exchange locks. Must be &ge; 1. */
  private int maxAttempts = 5;

  /** How long the authenticate exchange stays locked. Must be positive. */
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
