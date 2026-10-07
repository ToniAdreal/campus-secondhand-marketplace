package com.toni.marketplace.auth;

import java.util.List;

/**
 * Auth response body. The refresh token is never in the body — it travels in
 * the httpOnly {@code refresh_token} cookie set by {@link AuthController}.
 */
public record AuthResponse(
    String accessToken,
    String tokenType,
    long expiresInSeconds,
    UserSummary user) {

  public record UserSummary(long id, String username, String email, List<String> roles) {

    /**
     * Projection of the caller for session-restore responses. Built from the
     * entity deliberately instead of serializing {@link User} — the password
     * hash must never reach the wire.
     */
    public static UserSummary of(User user) {
      List<String> roles = user.getRoles().stream().map(Role::name).sorted().toList();
      return new UserSummary(user.getId(), user.getUsername(), user.getEmail(), roles);
    }
  }

  public static AuthResponse of(User user, JwtTokenService.TokenPair pair, long accessTtlSeconds) {
    return new AuthResponse(
        pair.accessToken(),
        "Bearer",
        accessTtlSeconds,
        UserSummary.of(user));
  }
}
