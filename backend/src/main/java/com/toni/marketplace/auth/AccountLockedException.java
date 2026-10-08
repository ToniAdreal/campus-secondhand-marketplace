package com.toni.marketplace.auth;

/**
 * Thrown by {@link AuthService#login} when the identified account is
 * currently locked after {@code app.auth.login-lockout.max-attempts}
 * consecutive failed logins (backlog #60). Carries the {@code Retry-After}
 * seconds so the client knows when to try again.
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
