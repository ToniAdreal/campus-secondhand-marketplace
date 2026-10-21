package com.toni.marketplace.common;

/**
 * Thrown when an email-change confirm presents a token that is unknown,
 * already used, or expired (backlog #208). One type and one message for
 * every failure kind — the caller cannot tell a guessed token from a
 * stale link — mapped to 401 in the envelope, mirroring the
 * password-reset confirm's credential semantics (the token IS the
 * credential for the switch), unlike the email-verification confirm's
 * 400 (confirming a registration address grants nothing by itself).
 */
public class InvalidEmailChangeTokenException extends RuntimeException {

  public InvalidEmailChangeTokenException() {
    super("invalid or expired email change token");
  }
}
