package com.toni.marketplace.auth;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditService;
import com.toni.marketplace.audit.AuditTargetType;
import com.toni.marketplace.common.InvalidCredentialsException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password-reset flow (backlog #90) — the recovery path for a user who has
 * lost their password (previously: none; a locked-out user had no way back
 * except an ADMIN, and no reset tooling existed at all).
 *
 * <ul>
 *   <li>{@code requestReset} answers identically whether or not the
 *       identifier resolves to an account (the controller returns the same
 *       200 envelope in both cases — no enumeration oracle, the same rule
 *       as login). For an existing, non-disabled account it issues a
 *       single-use token: 32 random bytes, Base64-URL encoded; only its
 *       SHA-256 hash is stored, with a 30-minute expiry
 *       ({@code app.auth.password-reset.token-ttl}). Issuing deletes the
 *       user's prior unused tokens, so a new request invalidates every
 *       earlier link. The raw token leaves the server exclusively through
 *       {@link PasswordResetMailSender}.</li>
 *   <li>{@code confirmReset} consumes the token: unknown, already-used and
 *       expired tokens all throw the identical
 *       {@link InvalidCredentialsException} (401, "invalid credentials").
 *       The new password must pass {@link PasswordStrengthValidator}
 *       (weak → 400, and the token is NOT consumed — the user may retry
 *       with a stronger one). On success the token is consumed, the
 *       password hash is replaced, {@code tokenVersion} is bumped and
 *       every refresh family is revoked — the same kill semantics as the
 *       authenticated password change (#38/#59), so all pre-reset sessions
 *       and Bearer tokens die immediately — and any login lockout (#60) is
 *       cleared: regaining the mailbox is a stronger identity proof than
 *       the lockout is defending against, and leaving the lock in place
 *       would keep the very user this flow exists for locked out. A
 *       successful confirm also appends a {@code PASSWORD_RESET_COMPLETED}
 *       audit row (backlog #110): the caller is a token holder, not an
 *       authenticated principal, but the token resolves to exactly one
 *       account, so actor and target are both that account — the row is
 *       evidence the account's credential changed via the reset path,
 *       distinct from {@code PASSWORD_CHANGED}'s authenticated change.
 *       Failed confirms (unknown/used/expired token, weak password)
 *       write no row, mirroring the audit trail's no-op-writes-none
 *       contract.</li>
 *   <li>Both endpoints are rate-limited per client IP in
 *       {@link AuthRateLimitFilter} (own bucket, default 5/min): the
 *       request endpoint is a mail-bombing surface and the confirm
 *       endpoint a token-guessing surface.</li>
 * </ul>
 *
 * <p>Honest scope: delivery is only as real as the injected
 * {@link PasswordResetMailSender} — the default bean logs (token redacted)
 * and sends nothing, because this project has no mail server. The SPA has
 * no reset pages yet; that frontend follow-up is deliberately out of scope
 * here (noted in the README).
 */
@Service
public class PasswordResetService {

  /** 32 random bytes per token (256 bits — unguessable, hash-only storage). */
  private static final int TOKEN_BYTES = 32;

  private final UserRepository users;
  private final PasswordResetTokenRepository resetTokens;
  private final PasswordService passwords;
  private final JwtTokenService jwt;
  private final PasswordResetMailSender mailSender;
  private final PasswordResetProperties props;
  private final Clock clock;
  private final AuditService audit;
  private final SecureRandom random = new SecureRandom();

  public PasswordResetService(UserRepository users, PasswordResetTokenRepository resetTokens,
                              PasswordService passwords, JwtTokenService jwt,
                              PasswordResetMailSender mailSender, PasswordResetProperties props,
                              Clock clock, AuditService audit) {
    this.users = users;
    this.resetTokens = resetTokens;
    this.passwords = passwords;
    this.jwt = jwt;
    this.mailSender = mailSender;
    this.props = props;
    this.clock = clock;
    this.audit = audit;
  }

  /**
   * Audit accessor with a no-op fallback, mirroring {@code AuthService}:
   * a hand-built instance without an AuditService must never fail on the
   * trail. Production always gets the Spring bean.
   */
  private AuditService audit() {
    return audit != null ? audit : AuditService.noop();
  }

  /**
   * Starts a reset for the account behind {@code usernameOrEmail} (username
   * or email, case-insensitive — the login rule). Unknown identifiers and
   * ADMIN-disabled accounts silently issue nothing; the caller cannot tell
   * the difference from the response.
   */
  @Transactional
  public void requestReset(String usernameOrEmail) {
    String identifier = AuthService.canonical(usernameOrEmail);
    User user = users.findByUsernameIgnoreCase(identifier)
        .or(() -> users.findByEmailIgnoreCase(identifier))
        .orElse(null);
    if (user == null || user.isDisabled()) {
      return;
    }
    // A fresh request supersedes every earlier unused token.
    resetTokens.deleteByUserIdAndUsedAtIsNull(user.getId());
    byte[] raw = new byte[TOKEN_BYTES];
    random.nextBytes(raw);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    Instant expiresAt = clock.instant().plus(props.getTokenTtl());
    resetTokens.save(new PasswordResetToken(user.getId(), JwtTokenService.sha256Hex(token),
        expiresAt));
    mailSender.sendPasswordReset(user, token);
  }

  /**
   * Consumes a reset token and sets the new password. Every token failure
   * (unknown / used / expired / orphaned) is the identical 401; a weak new
   * password is 400 and leaves the token usable.
   */
  @Transactional
  public void confirmReset(String rawToken, String newPassword) {
    PasswordResetToken row = resetTokens.findByTokenHash(JwtTokenService.sha256Hex(rawToken))
        .orElseThrow(InvalidCredentialsException::new);
    Instant now = clock.instant();
    if (row.isUsed() || row.isExpired(now)) {
      throw new InvalidCredentialsException();
    }
    User user = users.findById(row.getUserId()).orElseThrow(InvalidCredentialsException::new);
    // Strength is checked before consumption: a rejected password must not
    // burn the user's one live token.
    PasswordStrengthValidator.requireStrong(newPassword);
    user.setPasswordHash(passwords.encode(newPassword));
    // Same kill semantics as AuthService.changePassword: the version bump
    // kills pre-reset Bearer tokens at the filter, revokeAll kills every
    // refresh family (all sessions, all devices).
    user.setTokenVersion(user.getTokenVersion() + 1);
    // Mailbox recovery clears a login lockout (see class javadoc).
    user.setFailedLoginAttempts(0);
    user.setLockedUntil(null);
    users.save(user);
    jwt.revokeAll(user.getId());
    row.setUsedAt(now);
    resetTokens.save(row);
    resetTokens.deleteByUserIdAndUsedAtIsNull(user.getId());
    // Backlog #110: append the audit row inside this transaction, so a
    // rolled-back confirm leaves none. Actor = target = the account whose
    // password was reset (see the class javadoc for the attribution
    // decision); every failure path above returns/throws before this line
    // and writes no row.
    audit().record(user.getId(), AuditAction.PASSWORD_RESET_COMPLETED,
        AuditTargetType.USER, user.getId());
  }
}
