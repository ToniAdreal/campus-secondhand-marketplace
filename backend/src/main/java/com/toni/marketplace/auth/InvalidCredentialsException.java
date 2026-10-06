package com.toni.marketplace.auth;

/**
 * Thrown on login when the identifier is unknown or the password does not
 * match. A single exception type keeps both cases indistinguishable to the
 * caller (no user-enumeration oracle).
 */
public class InvalidCredentialsException extends RuntimeException {

  public InvalidCredentialsException() {
    super("invalid credentials");
  }
}
