package com.toni.marketplace.common;

/** Thrown when a username or email is already taken at registration. */
public class DuplicateUserException extends RuntimeException {

  public DuplicateUserException(String message) {
    super(message);
  }
}
