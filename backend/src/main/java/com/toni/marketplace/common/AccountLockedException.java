package com.toni.marketplace.common;

/**
 * Thrown when an account is temporarily locked after repeated failures:
 * by {@code AuthService#login} when {@code app.auth.login-lockout.max-attempts}
 * consecutive failed logins lock the password stage (backlog #60), and by
 * {@code AuthService#authenticateTotp} when
 * {@code app.auth.totp-lockout.max-attempts} consecutive failed second-factor
 * exchanges lock that exchange (backlog #101 — a separate lock that never
 * blocks password login). Carries the {@code Retry-After} seconds so the
 * client knows when to try again.
 *
 * <p>This exception only ever fires for <em>existing</em> accounts — unknown
 * identifiers keep getting the identical 401
 * ({@link InvalidCredentialsException}), so the 423 itself is not a new
 * user-enumeration oracle beyond what a lockout policy inherently implies.
 */
public class AccountLockedException extends RuntimeException {

  private final long retryAfterSeconds;

  public AccountLockedException(long retryAfterSeconds) {
    super("account temporarily locked — too many failed sign-in attempts");
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long getRetryAfterSeconds() {
    return retryAfterSeconds;
  }
}
