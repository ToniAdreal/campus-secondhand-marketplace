package com.toni.marketplace.auth;

/**
 * Registration response when email verification is pending (backlog #138):
 * the account exists but NO session was issued — no access token, no
 * refresh cookie — so this body deliberately does not reuse
 * {@link AuthResponse} (whose shape promises a token pair). The user
 * summary lets the SPA render its "check your inbox" state addressed to
 * the right account; {@code emailVerificationRequired} is always true in
 * this shape (when the gate is off, registration answers the plain
 * {@link AuthResponse} with a session instead).
 */
public record RegisterResponse(AuthResponse.UserSummary user, boolean emailVerificationRequired) {

  public static RegisterResponse pendingVerification(User user) {
    return new RegisterResponse(AuthResponse.UserSummary.of(user), true);
  }
}
