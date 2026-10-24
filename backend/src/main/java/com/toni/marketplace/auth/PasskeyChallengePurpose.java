package com.toni.marketplace.auth;

/**
 * Which ceremony a {@link PasskeyChallenge} row belongs to (backlog #220):
 * the enrolment ceremony (an authenticated user registering a new
 * passkey) or the login ceremony (answering the 202 second-factor
 * challenge with a passkey assertion). The two challenges are never
 * interchangeable — a registration challenge presented at the assertion
 * endpoint (or vice versa) is rejected, so a challenge minted for one
 * ceremony cannot be replayed into the other.
 */
public enum PasskeyChallengePurpose {
  REGISTRATION,
  ASSERTION
}
