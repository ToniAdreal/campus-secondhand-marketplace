package com.toni.marketplace.auth;

import java.time.Instant;
import java.util.List;

/**
 * The HTTP 202 body for a password-correct login on a second-factor
 * account: a short-lived signed challenge the client presents to
 * {@code POST /api/auth/2fa/authenticate} together with the second
 * factor. No token pair and no refresh cookie are issued at this point.
 *
 * <p>{@code factors} advertises which proofs the exchange accepts for
 * this account (backlog #220): {@code "totp"} when TOTP is enrolled,
 * {@code "passkey"} when at least one passkey is enrolled — the SPA
 * renders exactly those options. The 202 is issued only after the
 * password verified, so advertising the enrolled factors reveals
 * nothing to anyone who does not hold the password.
 */
public record TotpChallengeResponse(String challenge, Instant expiresAt,
                                     List<String> factors) {

  /** Two-arg form for call sites that predate the factor advertisement (backlog #220). */
  public TotpChallengeResponse(String challenge, Instant expiresAt) {
    this(challenge, expiresAt, List.of("totp"));
  }
}
