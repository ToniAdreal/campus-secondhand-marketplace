package com.toni.marketplace.common;

/**
 * A raw password fails the strength policy — see
 * {@link PasswordStrengthValidator}. Carries the user-facing rejection
 * reason; mapped to a 400 JSON envelope by the global exception handler.
 */
public class WeakPasswordException extends RuntimeException {

  public WeakPasswordException(String message) {
    super(message);
  }
}
