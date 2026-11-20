package com.toni.marketplace.auth;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only {@link PasswordResetMailSender} (backlog #407, step C1
 * unblocking the #399 Playwright password-reset journey): instead of
 * delivering the token anywhere, it records the latest raw token per
 * user id in memory, where the test-only read endpoint
 * ({@link E2ePasswordResetTokenController}) can serve it back.
 *
 * <p>Wired only when {@code app.auth.password-reset.
 * e2e-token-capture-enabled} is true (see {@link AuthConfig}); with the
 * knob off the default {@link LoggingPasswordResetMailSender} is the
 * bean and this class never exists in the context. The knob is set only
 * on the throwaway Playwright e2e backend, never on a real deployment:
 * with it on, the read endpoint hands a live reset token to anyone who
 * asks for the user id.
 *
 * <p>The store mirrors issuance, not validity: a token stays recorded
 * after it is confirmed or expires, and a fresh request overwrites the
 * previous token for the same user (a new request invalidates earlier
 * links — see {@link PasswordResetService#requestReset}). Whether a
 * recorded token still works is the confirm endpoint's business, never
 * this store's; the read endpoint documents the same. Password-reset
 * scope only: the email-verification (#138) and pending-email-change
 * (#208) seams stay separate per the own-purpose rule.
 */
public class CapturingPasswordResetMailSender implements PasswordResetMailSender {

  /** Latest raw token per user id; overwritten on each new request. */
  private final ConcurrentHashMap<Long, String> tokensByUserId = new ConcurrentHashMap<>();

  @Override
  public void sendPasswordReset(User user, String rawToken) {
    tokensByUserId.put(user.getId(), rawToken);
  }

  /** The latest token recorded for {@code userId}, if any was issued. */
  public Optional<String> findToken(long userId) {
    return Optional.ofNullable(tokensByUserId.get(userId));
  }
}
