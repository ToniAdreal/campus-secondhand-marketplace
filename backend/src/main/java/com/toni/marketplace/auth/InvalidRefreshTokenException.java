package com.toni.marketplace.auth;

/**
 * Thrown when a refresh token cannot be used: unknown, expired, revoked, or
 * reused. Reuse (replay of an already-rotated token) is treated as a possible
 * token theft and revokes the whole token family for the user — except for a
 * just-rotated token replayed inside the grace window (backlog #39), which is
 * accepted once more; see {@link JwtTokenService}.
 */
public class InvalidRefreshTokenException extends InvalidTokenException {

  public InvalidRefreshTokenException(String message) {
    super(message);
  }
}
