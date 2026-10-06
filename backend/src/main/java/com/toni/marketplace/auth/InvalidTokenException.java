package com.toni.marketplace.auth;

/** Thrown when an access/refresh token is malformed, tampered with, or expired. */
public class InvalidTokenException extends RuntimeException {

  public InvalidTokenException(String message) {
    super(message);
  }

  public InvalidTokenException(String message, Throwable cause) {
    super(message, cause);
  }
}
