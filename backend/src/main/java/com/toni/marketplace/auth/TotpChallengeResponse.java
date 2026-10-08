package com.toni.marketplace.auth;

import java.time.Instant;

/**
 * The HTTP 202 body for a password-correct login on a TOTP-enabled account:
 * a short-lived signed challenge the client presents to
 * {@code POST /api/auth/2fa/authenticate} together with the 6-digit code.
 * No token pair and no refresh cookie are issued at this point.
 */
public record TotpChallengeResponse(String challenge, Instant expiresAt) {}
