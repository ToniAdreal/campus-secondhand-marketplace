package com.toni.marketplace.auth;

import com.toni.marketplace.common.InvalidEmailVerificationTokenException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email-verification flow (backlog #138) — before this, registration never
 * verified the address: there was no verified flag on the user, and
 * {@code AuthService.register} returned a live token pair immediately, so
 * a typo'd or someone else's address became a fully usable account (and
 * the password-reset mail of backlog #90 later went to an address the
 * account holder might not control). The machinery mirrors that reset
 * flow:
 *
 * <ul>
 *   <li>{@code issue} creates a single-use token for a fresh account: 32
 *       random bytes, Base64-URL encoded; only its SHA-256 hash is stored,
 *       with a 24-hour expiry ({@code app.auth.email-verification
 *       .token-ttl}). Issuing deletes the user's prior unused tokens, so a
 *       resend invalidates every earlier link. The raw token leaves the
 *       server exclusively through {@link EmailVerificationMailSender}.</li>
 *   <li>{@code resend} answers identically whether or not the address
 *       resolves to an account (the controller returns the same 200
 *       envelope in both cases — no enumeration oracle). Only an existing,
 *       non-disabled, still-unverified account is issued a fresh token;
 *       every other state silently issues nothing.</li>
 *   <li>{@code confirm} consumes the token: unknown, already-used and
 *       expired tokens all throw the identical
 *       {@link InvalidEmailVerificationTokenException} (400). On success
 *       the account's {@code emailVerified} flag flips on and the token
 *       is consumed. Confirming grants NO session by itself — the user
 *       logs in with their password afterwards, like any other login.</li>
 *   <li>Both public endpoints are rate-limited per client IP in
 *       {@link AuthRateLimitFilter} (own bucket, default 5/min): the
 *       resend endpoint is a mail-bombing surface and the confirm
 *       endpoint a token-guessing surface — the backlog #101 pattern of a
 *       dedicated bucket that never resets on success.</li>
 * </ul>
 *
 * <p>Honest scope: delivery is only as real as the injected
 * {@link EmailVerificationMailSender} — the default bean logs (token
 * redacted) and sends nothing, because this project has no mail server.
 * Existing accounts are untouched: Flyway V26 backfills
 * {@code email_verified=true}, so the gate applies to new signups only.
 */
@Service
public class EmailVerificationService {

  /** 32 random bytes per token (256 bits — unguessable, hash-only storage). */
  private static final int TOKEN_BYTES = 32;

  private final UserRepository users;
  private final EmailVerificationTokenRepository verificationTokens;
  private final EmailVerificationMailSender mailSender;
  private final EmailVerificationProperties props;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public EmailVerificationService(UserRepository users,
                                  EmailVerificationTokenRepository verificationTokens,
                                  EmailVerificationMailSender mailSender,
                                  EmailVerificationProperties props,
                                  Clock clock) {
    this.users = users;
    this.verificationTokens = verificationTokens;
    this.mailSender = mailSender;
    this.props = props;
    this.clock = clock;
  }

  /**
   * Issues a fresh verification token for {@code user} and hands it to the
   * mail seam. Called from {@code AuthService.register} inside the
   * registration transaction, so a rolled-back registration leaves no
   * token row and sends no mail. Any prior unused token is deleted first:
   * at most one live link exists per account.
   */
  @Transactional
  public void issue(User user) {
    verificationTokens.deleteByUserIdAndUsedAtIsNull(user.getId());
    byte[] raw = new byte[TOKEN_BYTES];
    random.nextBytes(raw);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    Instant expiresAt = clock.instant().plus(props.getTokenTtl());
    verificationTokens.save(new EmailVerificationToken(user.getId(),
        JwtTokenService.sha256Hex(token), expiresAt));
    mailSender.sendEmailVerification(user, token);
  }

  /**
   * Re-issues a verification token for the account behind {@code email}
   * (case-insensitive — the registration rule). Unknown addresses,
   * already-verified accounts and ADMIN-disabled accounts silently issue
   * nothing; the caller cannot tell the difference from the response.
   */
  @Transactional
  public void resend(String email) {
    User user = users.findByEmailIgnoreCase(AuthService.canonical(email)).orElse(null);
    if (user == null || user.isDisabled() || user.isEmailVerified()) {
      return;
    }
    issue(user);
  }

  /**
   * Consumes a verification token and marks its account verified. Every
   * token failure (unknown / used / expired / orphaned) is the identical
   * 400. No session is issued here — verification proves mailbox control,
   * the password still proves the rest at login.
   */
  @Transactional
  public void confirm(String rawToken) {
    EmailVerificationToken row = verificationTokens
        .findByTokenHash(JwtTokenService.sha256Hex(rawToken))
        .orElseThrow(InvalidEmailVerificationTokenException::new);
    Instant now = clock.instant();
    if (row.isUsed() || row.isExpired(now)) {
      throw new InvalidEmailVerificationTokenException();
    }
    User user = users.findById(row.getUserId())
        .orElseThrow(InvalidEmailVerificationTokenException::new);
    user.setEmailVerified(true);
    users.save(user);
    row.setUsedAt(now);
    verificationTokens.save(row);
    verificationTokens.deleteByUserIdAndUsedAtIsNull(user.getId());
  }
}
