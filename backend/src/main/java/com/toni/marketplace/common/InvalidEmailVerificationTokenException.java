package com.toni.marketplace.common;

/**
 * Thrown when an email-verification confirm presents a token that is
 * unknown, already used, or expired (backlog #138). One type and one
 * message for every failure kind — the caller cannot tell a guessed token
 * from a stale link — mapped to 400 in the envelope (a client-state
 * problem the user fixes by requesting a fresh link, unlike the
 * password-reset confirm's 401 credential semantics).
 */
public class InvalidEmailVerificationTokenException extends RuntimeException {

  public InvalidEmailVerificationTokenException() {
    super("invalid or expired verification token");
  }
}
