package com.toni.marketplace.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditService;
import com.toni.marketplace.audit.AuditTargetType;
import com.toni.marketplace.common.AccountLockedException;
import com.toni.marketplace.common.DuplicateUserException;
import com.toni.marketplace.common.InvalidCredentialsException;
import com.toni.marketplace.common.InvalidTotpCodeException;
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
  private final TotpService totp;
  private final TotpSecretCipher totpCipher;
  private final TotpRecoveryCodeRepository recoveryCodes;
  private final Clock clock;
  private final LoginLockoutProperties lockout;
  private final AuthMetrics metrics;
  private final AuditService audit;

  @Autowired
  public AuthService(UserRepository users, PasswordService passwords, JwtTokenService jwt,
                     TotpService totp, TotpSecretCipher totpCipher,
                     TotpRecoveryCodeRepository recoveryCodes, Clock clock,
                     LoginLockoutProperties lockout, AuthMetrics metrics, AuditService audit) {
    this.users = users;
    this.passwords = passwords;
    this.jwt = jwt;
    this.totp = totp;
    this.totpCipher = totpCipher;
    this.recoveryCodes = recoveryCodes;
    this.clock = clock;
    this.lockout = lockout;
    this.metrics = metrics;
    this.audit = audit;
  }

  /** Legacy constructor for direct unit tests that do not assert on metrics. */
  public AuthService(UserRepository users, PasswordService passwords, JwtTokenService jwt,
                     TotpService totp, Clock clock, LoginLockoutProperties lockout) {
    this(users, passwords, jwt, totp,
        new TotpSecretCipher(TotpEncryptionKeyStartupCheck.DEV_PLACEHOLDER_KEY),
        null, clock, lockout, AuthMetrics.noop(), AuditService.noop());
  }

  /**
   * Audit accessor with a no-op fallback, same rationale as
   * {@link #metrics()}: direct unit tests that construct this service
   * without an AuditService must never fail on the trail.
   */
  private AuditService audit() {
    return audit != null ? audit : AuditService.noop();
  }

  /**
   * Recovery-code persistence with the same null-fallback rationale as
   * {@link #metrics()}: the legacy constructor (direct unit tests) has no
   * repository, so recovery codes are simply unavailable there — enable
   * still returns the generated codes, authenticate finds none, and the
   * count reads 0. Production always gets the JPA bean.
   */
  private boolean recoveryCodesAvailable() {
    return recoveryCodes != null;
  }

  /** Registered user plus their first token pair. */
  public record AuthResult(User user, JwtTokenService.TokenPair pair) {}

  /**
   * Outcome of a password login (backlog #78). Most logins produce a
   * {@link Pair}; a login for a TOTP-enabled account produces a
   * {@link Challenge} — a short-lived signed token the client exchanges
   * (with a valid 6-digit code) at {@code POST /api/auth/2fa/authenticate}.
   * The sealed type forces every caller to handle both cases.
   */
  public sealed interface LoginResult permits LoginResult.Pair, LoginResult.Challenge {
    /** A finished login: user plus the token pair to return. */
    record Pair(AuthResult result) implements LoginResult {}
    /** A 2FA challenge: signed token plus its absolute expiry instant. */
    record Challenge(String challengeToken, Instant expiresAt) implements LoginResult {}
  }

  /**
   * Metrics accessor with a no-op fallback: Mockito {@code @InjectMocks}
   * in older unit tests constructs this service without an AuthMetrics
   * mock, leaving the field null — counting must never break a login.
   */
  private AuthMetrics metrics() {
    return metrics != null ? metrics : AuthMetrics.noop();
  }

  /**
   * Cipher accessor with a dev-placeholder fallback, same rationale as
   * {@link #metrics()}: Mockito {@code @InjectMocks} in older unit tests
   * may leave the field null (or a mock whose {@code encrypt} returns
   * null). Falling back to the committed dev placeholder keeps those
   * tests' TOTP paths functional; production always gets the configured
   * cipher bean, and the mysql profile refuses the placeholder at
   * startup (see {@link TotpEncryptionKeyStartupCheck}).
   */
  private TotpSecretCipher cipher() {
    return totpCipher != null
        ? totpCipher
        : new TotpSecretCipher(TotpEncryptionKeyStartupCheck.DEV_PLACEHOLDER_KEY);
  }

  /**
   * Resolves a stored TOTP secret to its plaintext Base32 form (backlog
   * #89): decrypts the {@code v1:} form, passes a legacy pre-#89 plaintext
   * secret through. A tampered or undecryptable stored value is translated
   * by the caller into the same failure as a wrong code — the decryption
   * error itself never leaks into an API response.
   */
  private String resolveTotpSecret(String stored) {
    return cipher().decryptOrLegacy(stored);
  }

  /** Shared secret plus provisioning URI returned by the 2FA setup step. */
  public record TotpSetup(String secret, String otpauthUri) {}

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

  public LoginResult login(String usernameOrEmail, String rawPassword) {
    return login(usernameOrEmail, rawPassword, SessionMeta.unknown());
  }

  /**
   * Verifies credentials. The identifier is normalized before lookup, so
   * "ALICE" logs into the "alice" account. Unknown identifier and wrong
   * password produce the identical error — no user enumeration.
   *
   * <p>Per-account lockout (backlog #60): consecutive failures are counted on
   * the user row; after {@code app.auth.login-lockout.max-attempts} failures
   * the account is locked for {@code lock-duration} and this throws
   * {@link AccountLockedException} (mapped to 423 + {@code Retry-After}) even
   * for the correct password. A successful login resets the counter; an
   * expired lock resets the counter on the next attempt. Unknown identifiers
   * are never tracked — they keep getting the identical 401.
   *
   * <p>ADMIN-disabled accounts (backlog #88) get the identical 401, checked
   * before the password so the response leaks nothing about the account's
   * state or its password.
   *
   * <p>TOTP two-factor (backlog #78): when the password is correct but the
   * account has 2FA enabled, no token pair is issued — the result is a
   * {@link LoginResult.Challenge} the client exchanges for the pair at
   * {@code POST /api/auth/2fa/authenticate}.
   *
   * @param meta device info captured from the request — recorded on the new
   *     session's refresh-token row (backlog #62)
   */
  @Transactional
  public LoginResult login(String usernameOrEmail, String rawPassword, SessionMeta meta) {
    String identifier = canonical(usernameOrEmail);
    User user = users.findByUsernameIgnoreCase(identifier)
        .or(() -> users.findByEmailIgnoreCase(identifier))
        .orElseGet(() -> {
          // Unknown identifier: a login failure, but never lockout-tracked
          // (no account exists to lock — see the class-level #60 rule).
          metrics().loginFailure();
          return null;
        });
    if (user == null) {
      throw new InvalidCredentialsException();
    }
    if (user.isDisabled()) {
      // ADMIN-disabled account (backlog #88): the identical 401 as a bad
      // password, checked before the password so a disabled account gives
      // no signal about its password's validity either. Not lockout-tracked
      // — the account is already fully off.
      metrics().loginFailure();
      throw new InvalidCredentialsException();
    }
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
      metrics().loginFailure();
      if (lockout.isEnabled()) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        if (attempts >= lockout.getMaxAttempts()) {
          Instant until = now.plus(lockout.getLockDuration());
          user.setLockedUntil(until);
          metrics().lockoutTriggered();
          throw new AccountLockedException(lockout.getLockDuration().getSeconds());
        }
      }
      throw new InvalidCredentialsException();
    }
    // Password verified: the login succeeded at this stage — for a
    // TOTP-enabled account the challenge issuance below still counts
    // (see AuthMetrics javadoc).
    metrics().loginSuccess();
    if (lockout.isEnabled()
        && (user.getFailedLoginAttempts() != 0 || user.getLockedUntil() != null)) {
      user.setFailedLoginAttempts(0);
      user.setLockedUntil(null);
    }
    if (user.isTotpEnabled()) {
      // Password correct, second factor still outstanding: issue a
      // short-lived signed challenge, never a token pair.
      Instant expiresAt = now.plus(jwt.getTotpChallengeTtl());
      return new LoginResult.Challenge(jwt.createTotpChallenge(user), expiresAt);
    }
    return new LoginResult.Pair(new AuthResult(user, jwt.createTokenPair(user, meta)));
  }

  /**
   * Starts TOTP enrollment (backlog #78): generates a fresh shared secret,
   * stores it on the user row with {@code totpEnabled=false}, and returns
   * the secret plus the otpauth:// provisioning URI for the authenticator
   * app. Re-running setup regenerates the secret and resets enrollment —
   * the previous authenticator stops working until /enable is completed
   * again. The endpoint requires authentication (SecurityConfig), so only
   * the account holder (or someone holding their session) can enroll.
   *
   * <p>Encryption at rest (backlog #89): only the {@link TotpSecretCipher}
   * {@code v1:} form reaches the database; the plaintext secret exists
   * solely in this response (the one moment the user provisions their
   * authenticator app).
   */
  @Transactional
  public TotpSetup setupTotp(long userId) {
    User user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
    String secret = totp.generateSecret();
    user.setTotpSecret(cipher().encrypt(secret));
    user.setTotpEnabled(false);
    users.save(user);
    // Backlog #91: the previous enrollment's recovery codes belong to
    // the secret just replaced — retire the whole set now, so a code
    // printed for the old enrollment can never authenticate against
    // the new one (enable issues a fresh set).
    if (recoveryCodesAvailable()) {
      recoveryCodes.deleteByUserId(userId);
    }
    return new TotpSetup(secret, totp.otpauthUri("CampusMarketplace", user.getUsername(), secret));
  }

  /**
   * Completes TOTP enrollment: a valid 6-digit code (verified against the
   * stored secret, ±1 step of clock skew) flips {@code totpEnabled} on.
   * A wrong code → 400 {@link InvalidTotpCodeException}; no setup yet →
   * 400 as well (the message says which).
   *
   * <p>Backlog #89: the stored secret is resolved through {@link
   * TotpSecretCipher#decryptOrLegacy} — a tampered stored value fails
   * closed as the same 400 as a wrong code, and a legacy plaintext secret
   * (written before #89) is re-encrypted in place on success.
   *
   * <p>Backlog #91: enabling also issues {@link TotpService#RECOVERY_CODE_COUNT}
   * one-time recovery codes and returns them in display form — the only
   * moment they exist in plaintext anywhere. Only their SHA-256 hashes
   * are stored; any previous set is deleted first, so re-enrollment
   * invalidates the old codes.
   *
   * @return the freshly issued recovery codes, display form, shown once
   */
  @Transactional
  public List<String> enableTotp(long userId, String code) {
    User user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
    String stored = user.getTotpSecret();
    if (stored == null) {
      throw new InvalidTotpCodeException("run the 2fa setup step first");
    }
    final String secret;
    try {
      secret = resolveTotpSecret(stored);
    } catch (TotpSecretCipher.DecryptionException e) {
      throw new InvalidTotpCodeException("invalid two-factor code");
    }
    if (!totp.verify(secret, code)) {
      throw new InvalidTotpCodeException("invalid two-factor code");
    }
    user.setTotpEnabled(true);
    if (!cipher().isEncrypted(stored)) {
      // Legacy plaintext row: upgrade it to the encrypted form now.
      user.setTotpSecret(cipher().encrypt(secret));
    }
    users.save(user);
    // Backlog #98: the enable is a sensitive transition — append its audit
    // row inside this transaction (a rollback above leaves no row).
    audit().record(userId, AuditAction.TOTP_ENABLED, AuditTargetType.USER, userId);
    return issueRecoveryCodes(userId);
  }

  /**
   * Replaces the user's recovery-code set with a fresh one and returns
   * the display forms (backlog #91). Generation retries on the
   * (astronomically unlikely) hash collision with another user's code
   * so the unique constraint never surfaces as a 500.
   */
  private List<String> issueRecoveryCodes(long userId) {
    java.util.List<String> issued = new java.util.ArrayList<>();
    if (!recoveryCodesAvailable()) {
      for (int i = 0; i < TotpService.RECOVERY_CODE_COUNT; i++) {
        issued.add(totp.generateRecoveryCode());
      }
      return issued;
    }
    recoveryCodes.deleteByUserId(userId);
    java.util.Set<String> seen = new java.util.HashSet<>();
    while (issued.size() < TotpService.RECOVERY_CODE_COUNT) {
      String code = totp.generateRecoveryCode();
      String hash = JwtTokenService.sha256Hex(TotpService.normalizeRecoveryCode(code));
      if (!seen.add(hash) || recoveryCodes.existsByCodeHash(hash)) {
        continue; // duplicate within the set, or a one-in-2^80 hash clash
      }
      recoveryCodes.save(new TotpRecoveryCode(userId, hash));
      issued.add(code);
    }
    return issued;
  }

  /**
   * Consumes one recovery code for the user (backlog #91): the presented
   * code is normalized (separators/case-insensitive) and hashed; a live
   * row is stamped used and the code can never authenticate again. Any
   * other outcome — malformed input, unknown code, already used — is
   * {@code false}, and the caller folds it into the identical 401 it
   * returns for a wrong TOTP code.
   */
  private boolean consumeRecoveryCode(long userId, String presented) {
    String normalized = TotpService.normalizeRecoveryCode(presented);
    if (normalized == null || !recoveryCodesAvailable()) {
      return false;
    }
    return recoveryCodes
        .findByUserIdAndCodeHashAndUsedAtIsNull(userId, JwtTokenService.sha256Hex(normalized))
        .map(row -> {
          row.setUsedAt(clock.instant());
          recoveryCodes.save(row);
          return true;
        })
        .orElse(false);
  }

  /** How many of the user's recovery codes remain unused (backlog #91). */
  @Transactional(readOnly = true)
  public long recoveryCodeCount(long userId) {
    return recoveryCodesAvailable()
        ? recoveryCodes.countByUserIdAndUsedAtIsNull(userId)
        : 0;
  }

  /**
   * Exchanges a 2FA challenge (plus a valid 6-digit code — or, backlog
   * #91, a one-time recovery code, consumed by the exchange) for the real token
   * pair. A bad/expired challenge or a wrong code → 401 with the identical
   * message — the caller already proved the password to obtain the
   * challenge, so there is no enumeration oracle here, but the uniform
   * message keeps the failure mode boring on purpose.
   *
   * <p>Honest scope: failed TOTP attempts do not feed the per-account login
   * lockout (#60 counts password failures only), and the authenticate
   * endpoint has no dedicated rate limit yet — both are declared follow-ups.
   *
   * @param meta device info captured from the request — recorded on the new
   *     session's refresh-token row (backlog #62)
   */
  @Transactional
  public AuthResult authenticateTotp(String challenge, String code, SessionMeta meta) {
    long userId = jwt.parseTotpChallenge(challenge);
    User user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
    String stored = user.getTotpSecret();
    if (user.isDisabled() || !user.isTotpEnabled() || stored == null) {
      throw new InvalidCredentialsException();
    }
    // Backlog #89: resolve the stored secret (decrypt v1:, pass legacy
    // plaintext through). Tampered/undecryptable storage fails closed as
    // the identical 401 — never an exception leak.
    final String secret;
    try {
      secret = resolveTotpSecret(stored);
    } catch (TotpSecretCipher.DecryptionException e) {
      throw new InvalidCredentialsException();
    }
    if (!totp.verify(secret, code)) {
      // Backlog #91: not a TOTP code — try it as a one-time recovery
      // code. A live code is consumed by this exchange; a replay, an
      // unknown code and a wrong TOTP code all land on the identical
      // 401 below, so the response never reveals which kind was tried.
      if (!consumeRecoveryCode(userId, code)) {
        throw new InvalidCredentialsException();
      }
    }
    if (!cipher().isEncrypted(stored)) {
      // Legacy plaintext row (enabled before #89): re-encrypt in place on
      // this successful authenticate, so plaintext secrets drain out of
      // the table without waiting for a re-enrollment.
      user.setTotpSecret(cipher().encrypt(secret));
      users.save(user);
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
    metrics().passwordChanged();
    // Backlog #98: append the audit row inside this transaction, so a
    // change that rolls back (e.g. token issuance failing) leaves no row.
    audit().record(userId, AuditAction.PASSWORD_CHANGED, AuditTargetType.USER, userId);
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

  /**
   * ADMIN user management (backlog #88): disables an account. The flag is
   * enforced at login (identical 401) and on every authenticated request
   * ({@link JwtAuthenticationFilter}); disabling also revokes every
   * refresh-token family of the account and bumps its tokenVersion, so all
   * sessions die immediately and pre-disable Bearer tokens stay dead even
   * after a later re-enable (the user logs in fresh).
   *
   * <p>Guards, both answered 403: an admin cannot disable themselves (an
   * account must never be able to lock out its own operator mid-incident),
   * and an admin cannot disable another ADMIN (admin accounts are managed
   * out-of-band, never through this endpoint — a compromised admin session
   * must not be able to wipe out the other operators). Unknown id → 404.
   * Re-disabling an already-disabled account is an idempotent no-op.
   */
  @Transactional
  public User disableUser(long actorId, long targetId) {
    User target = users.findById(targetId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    if (actorId == targetId) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cannot disable yourself");
    }
    if (target.getRoles().contains(Role.ADMIN)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cannot disable an admin user");
    }
    if (target.isDisabled()) {
      return target;
    }
    target.setDisabled(true);
    target.setTokenVersion(target.getTokenVersion() + 1);
    users.save(target);
    jwt.revokeAll(targetId);
    // Backlog #98: the acting ADMIN is the audit actor, the disabled
    // account the target. The already-disabled no-op above returns before
    // this point and writes no row.
    audit().record(actorId, AuditAction.USER_DISABLED, AuditTargetType.USER, targetId);
    return target;
  }

  /**
   * Re-enables a disabled account (backlog #88): clears the flag so the
   * user can log in again. No guard on the target's role — enabling is
   * restorative, and a disabled admin cannot authenticate to abuse it.
   * Unknown id → 404. Re-enabling an enabled account is an idempotent
   * no-op. Pre-disable tokens are NOT resurrected (disable bumped
   * tokenVersion); the user gets fresh tokens at login.
   */
  @Transactional
  public User enableUser(long actorId, long targetId) {
    User target = users.findById(targetId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    if (!target.isDisabled()) {
      return target;
    }
    target.setDisabled(false);
    users.save(target);
    // Backlog #98: actor = the acting ADMIN (mirrors disableUser); the
    // already-enabled no-op above writes no row.
    audit().record(actorId, AuditAction.USER_ENABLED, AuditTargetType.USER, targetId);
    return target;
  }
}
