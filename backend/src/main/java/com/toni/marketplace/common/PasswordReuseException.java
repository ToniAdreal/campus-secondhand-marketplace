package com.toni.marketplace.common;

/**
 * A password-change request reuses the current password. The candidate is
 * compared against the stored hash with {@code PasswordEncoder.matches}
 * before anything is encoded; mapped to a 400 JSON envelope by the global
 * exception handler. No new oracle: the caller already proved the current
 * password to reach this point.
 */
public class PasswordReuseException extends RuntimeException {

  public PasswordReuseException(String message) {
    super(message);
  }
}
