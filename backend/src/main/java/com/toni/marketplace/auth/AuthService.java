package com.toni.marketplace.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.toni.marketplace.common.AccountLockedException;
import com.toni.marketplace.common.DuplicateUserException;
import com.toni.marketplace.common.InvalidCredentialsException;
import com.toni.marketplace.common.PasswordReuseException;

/**
 * Registration / login / refresh flows. HTTP concerns (cookies, status codes)
 * live in {@link AuthController}; this class owns the domain logic.
 *
 * <p>Case rule: usernames and emails are normalized to lowercase
 * ({@link Locale#ROOT}) at registration and stored canonical; every lookup
 * is case-insensitive (see {@link UserRepository}). "Toni" and "toni" are
 * the same account on every database — before this change the duplicate
 * checks were case-sensitive Java-side, so H2 allowed both rows while MySQL's
 * case-insensitive collation threw a raw constraint error instead of 409.
 * Rows written before normalization are backfilled by
 * {@code V9__normalize_user_case.sql}; see its header for the
 * true-case-duplicate concern.
 */
@Service
public class AuthService {

  private final UserRepository users;
  private final PasswordService passwords;
  private final JwtTokenService jwt;
  private final Clock clock;
  private final LoginLockoutProperties lockout;

  public AuthService(UserRepository users, PasswordService passwords, JwtTokenService jwt,
                     Clock clock, LoginLockoutProperties lockout) {
    this.users = users;
    this.passwords = passwords;
    this.jwt = jwt;
    this.clock = clock;
    this.lockout = lockout;
  }

  /** Registered user plus their first token pair. */
  public record AuthResult(User user, JwtTokenService.TokenPair pair) {}

  /** Canonical form for usernames and emails. {@code Locale.ROOT} avoids the Turkish-I surprise. */
  static String canonical(String raw) {
    return raw.toLowerCase(Locale.ROOT);
  }

  public AuthResult register(String username, String email, String rawPassword) {
    return register(username, email, rawPassword, SessionMeta.unknown());
  }

  /**
   * Registers a new user. Weak passwords → 400 (see
   * {@link PasswordStrengthValidator}); duplicate username or email → 409.
   * The username and email are stored canonical-lowercase, so the response
   * echoes the canonical form ("Alice" registers as "alice").
   *
   * @param meta device info captured from the request — recorded on the new
   *     session's refresh-token row (backlog #62)
   */
  @Transactional
  public AuthResult register(String username, String email, String rawPassword, SessionMeta meta) {
    PasswordStrengthValidator.requireStrong(rawPassword);
    String canonicalUsername = canonical(username);
    String canonicalEmail = canonical(email);
    if (users.findByUsernameIgnoreCase(canonicalUsername).isPresent()) {
      throw new DuplicateUserException("username is already taken");
    }
    if (users.findByEmailIgnoreCase(canonicalEmail).isPresent()) {
      throw new DuplicateUserException("email is already registered");
    }
    User user = users.save(new User(canonicalUsername, canonicalEmail, passwords.encode(rawPassword)));
    return new AuthResult(user, jwt.createTokenPair(user, meta));
  }

  public AuthResult login(String usernameOrEmail, String rawPassword) {
    return login(usernameOrEmail, rawPassword, SessionMeta.unknown());
  }

  /**
   * Verifies credentials and issues a fresh token pair. The identifier is
   * normalized before lookup, so "ALICE" logs into the "alice" account.
   * Unknown identifier and wrong password produce the identical error —
   * no user enumeration.
   *
   * <p>Per-account lockout (backlog #60): consecutive failures are counted on
   * the user row; after {@code app.auth.login-lockout.max-attempts} failures
   * the account is locked for {@code lock-duration} and this throws
   * {@link AccountLockedException} (mapped to 423 + {@code Retry-After}) even
   * for the correct password. A successful login resets the counter; an
   * expired lock resets the counter on the next attempt. Unknown identifiers
   * are never tracked — they keep getting the identical 401.
   *
   * @param meta device info captured from the request — recorded on the new
   *     session's refresh-token row (backlog #62)
   */
  @Transactional
  public AuthResult login(String usernameOrEmail, String rawPassword, SessionMeta meta) {
    String identifier = canonical(usernameOrEmail);
    User user = users.findByUsernameIgnoreCase(identifier)
        .or(() -> users.findByEmailIgnoreCase(identifier))
        .orElseThrow(InvalidCredentialsException::new);
    Instant now = clock.instant();
    if (lockout.isEnabled()) {
      Instant lockedUntil = user.getLockedUntil();
      if (lockedUntil != null) {
        if (now.isBefore(lockedUntil)) {
          throw new AccountLockedException(retryAfterSeconds(now, lockedUntil));
        }
        // Expired lock: the account gets a fresh allowance.
        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
      }
    }
    if (!passwords.matches(rawPassword, user.getPasswordHash())) {
      if (lockout.isEnabled()) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        if (attempts >= lockout.getMaxAttempts()) {
          Instant until = now.plus(lockout.getLockDuration());
          user.setLockedUntil(until);
          throw new AccountLockedException(lockout.getLockDuration().getSeconds());
        }
      }
      throw new InvalidCredentialsException();
    }
    if (lockout.isEnabled()
        && (user.getFailedLoginAttempts() != 0 || user.getLockedUntil() != null)) {
      user.setFailedLoginAttempts(0);
      user.setLockedUntil(null);
    }
    return new AuthResult(user, jwt.createTokenPair(user, meta));
  }

  /** Whole seconds until the lock expires, never below 1 (the header must be meaningful). */
  private static long retryAfterSeconds(Instant now, Instant lockedUntil) {
    return Math.max(1, Duration.between(now, lockedUntil).getSeconds());
  }

  /**
   * Consumes a refresh token and issues a fresh pair. The old token is
   * revoked by rotation; replaying it is treated as possible theft and
   * revokes the whole family — see {@link JwtTokenService#rotateRefreshToken}.
   */
  @Transactional
  public AuthResult refresh(String refreshToken) {
    return refresh(refreshToken, SessionMeta.unknown());
  }

  /**
   * Rotation variant that records the caller's device info
   * ({@link SessionMeta}) on the newly issued row (backlog #62).
   */
  @Transactional
  public AuthResult refresh(String refreshToken, SessionMeta meta) {
    JwtTokenService.TokenPair pair = jwt.rotateRefreshToken(refreshToken, meta);
    // Rotation re-reads the user for role changes; resolve it for the response.
    // (Only the stored hash is known here, so re-derive the user from the new
    // access token's claims — the subject is the user id.)
    long userId = jwt.parseAccessToken(pair.accessToken()).userId();
    User user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
    return new AuthResult(user, pair);
  }

  public AuthResult changePassword(long userId, String currentPassword, String newPassword) {
    return changePassword(userId, currentPassword, newPassword, SessionMeta.unknown());
  }

  /**
   * Changes the user's password. Requires the current password — a wrong
   * current password produces the identical 401 as a bad login, so no
   * oracle leaks whether the attempt was "wrong user" or "wrong password".
   * The new password must satisfy {@link PasswordStrengthValidator} (weak →
   * 400). On success the whole refresh-token family is revoked — every
   * other session dies — the row's tokenVersion is bumped so all
   * previously issued bearer tokens 401 immediately, and the caller's
   * session gets a fresh pair (minted at the new version), so they stay
   * logged in while stolen/other sessions are cut off.
   *
   * Reusing the current password is rejected with 400 (compared against the
   * stored hash via {@code PasswordEncoder.matches} before anything is
   * encoded — the caller already proved the current password, so this
   * leaks no new oracle).
   *
   * @param meta device info captured from the request — recorded on the new
   *     session's refresh-token row (backlog #62)
   */
  @Transactional
  public AuthResult changePassword(long userId, String currentPassword, String newPassword,
                                   SessionMeta meta) {
    User user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
    if (!passwords.matches(currentPassword, user.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }
    if (passwords.matches(newPassword, user.getPasswordHash())) {
      throw new PasswordReuseException("new password must differ from the current password");
    }
    PasswordStrengthValidator.requireStrong(newPassword);
    user.setPasswordHash(passwords.encode(newPassword));
    user.setTokenVersion(user.getTokenVersion() + 1);
    users.save(user);
    jwt.revokeAll(userId);
    return new AuthResult(user, jwt.createTokenPair(user, meta));
  }

  /**
   * Session restore for the SPA: resolves the caller behind a valid access
   * token to their profile. A valid token whose user row is gone (deleted
   * user) reads as invalid credentials — the session no longer exists.
   */
  @Transactional(readOnly = true)
  public User me(long userId) {
    return users.findById(userId).orElseThrow(InvalidCredentialsException::new);
  }

  /**
   * Logs out: deletes every refresh token of the user (all their sessions,
   * on all devices), so no presented refresh cookie can mint new access
   * tokens afterwards. The httpOnly cookie itself is cleared by the
   * controller with an expired {@code Set-Cookie}.
   */
  @Transactional
  public void logout(long userId) {
    jwt.revokeAll(userId);
  }

  /**
   * Per-session management (backlog #62): lists the caller's live
   * refresh-token sessions (one per family), most recently active first.
   * The session whose refresh token the caller presented (the
   * {@code refresh_token} cookie, matched via the token's jti → family) is
   * flagged {@code current}; an absent or unknown cookie flags nothing.
   */
  @Transactional(readOnly = true)
  public List<SessionDto> sessions(long userId, String presentedRefreshToken) {
    Instant now = clock.instant();
    String currentFamily = (presentedRefreshToken == null || presentedRefreshToken.isBlank())
        ? null
        : jwt.sessionFamilyOfToken(presentedRefreshToken).orElse(null);
    return jwt.liveSessionTokens(userId, now).stream()
        .map(row -> new SessionDto(row.getFamilyJti(), row.getUserAgent(), row.getIpAddress(),
            row.getFamilyIssuedAt(), row.getCreatedAt(),
            row.getFamilyJti().equals(currentFamily)))
        .sorted(Comparator.comparing(SessionDto::lastActiveAt).reversed())
        .toList();
  }

  /**
   * Revokes one session (its refresh-token family) without touching the
   * caller's other sessions. An unknown session id — or one belonging to
   * another user — is 404 (no cross-user oracle). Revoking the caller's own
   * current session takes effect on the next refresh: the dead cookie 401s
   * and the SPA re-logs-in.
   */
  @Transactional
  public void revokeSession(long userId, String familyJti) {
    if (!jwt.familyBelongsToUser(userId, familyJti)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
    }
    jwt.revokeFamily(familyJti);
  }
}
