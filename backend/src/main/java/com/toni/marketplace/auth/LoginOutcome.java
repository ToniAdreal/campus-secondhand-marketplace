package com.toni.marketplace.auth;

/**
 * Verdict of one login attempt, recorded on its {@code login_event} row
 * (backlog #171, Flyway V39). Exactly one value is recorded per attempt:
 * per password-stage attempt in {@code AuthService.loginAttempt} and per
 * second-factor exchange in {@code AuthService.authenticateTotp}.
 *
 * <p>The values deliberately distinguish cases the HTTP response folds
 * together: a disabled account and a wrong password both answer the
 * identical 401 (no state oracle), but the history — visible only to the
 * account holder through {@code GET /api/auth/login-history} — records
 * the disabled case as {@link #BAD_PASSWORD} so the folding stays
 * invisible from outside while the row set stays small and stable.
 */
public enum LoginOutcome {

  /** Password (and, where enabled, second factor) verified; a token pair was issued. */
  SUCCESS,

  /** The account exists but the password did not match (a disabled account folds into this value — see the class javadoc). */
  BAD_PASSWORD,

  /** No account matches the attempted identifier; the row's {@code user_id} is NULL. */
  UNKNOWN_USER,

  /** The attempt was refused by a lockout: either an already-active lock, or the failure that tripped one (login #60 / TOTP exchange #101). */
  LOCKED,

  /** Password verified on a TOTP-enabled account; a challenge was issued and the second factor is still outstanding. */
  TOTP_REQUIRED,

  /** The second-factor exchange failed: wrong code, wrong recovery code, or an account-state failure folded into the identical 401. */
  TOTP_FAILED,

  /**
   * Password verified but the #138 email-verification gate refused the
   * login (403). This value exists because that refusal is neither a
   * credential failure ({@link #BAD_PASSWORD} would lie — the password
   * matched) nor a success; without it the most common support question
   * ("why can't I sign in with the right password?") would be invisible
   * in the very history meant to answer it.
   */
  EMAIL_UNVERIFIED
}
