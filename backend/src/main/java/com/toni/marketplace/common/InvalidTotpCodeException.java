package com.toni.marketplace.common;

/**
 * A well-formed TOTP code that does not verify (or a 2FA enable attempt with
 * no setup behind it). Maps to 400 — the caller is authenticated and the
 * request itself is actionable, the code just does not check out. (A
 * well-formed-but-wrong code at the unauthenticated
 * {@code /2fa/authenticate} step is instead
 * {@link InvalidCredentialsException} → 401.)
 */
public class InvalidTotpCodeException extends RuntimeException {

  public InvalidTotpCodeException(String message) {
    super(message);
  }
}
