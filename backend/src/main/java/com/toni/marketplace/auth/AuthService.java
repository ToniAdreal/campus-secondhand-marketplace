package com.toni.marketplace.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import com.toni.marketplace.common.InvalidEmailChangeTokenException;
import com.toni.marketplace.common.InvalidTotpCodeException;
import com.toni.marketplace.common.PasswordReuseException;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.notification.NotificationService;
import com.toni.marketplace.notification.NotificationType;
import io.micrometer.core.instrument.Timer;

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
  private final TotpLockoutProperties totpLockout;
  private final AuthMetrics metrics;
  private final AuditService audit;
  private final PasswordPolicyProperties passwordPolicy;

  private final EmailVerificationService emailVerification;
  private final EmailVerificationProperties emailVerificationProps;
  private final PasswordHistoryService passwordHistory;
  private final ItemRepository items;
  private final LoginHistoryRecorder loginHistory;
  private final LoginEventRepository loginEvents;
  private final NotificationService notifications;
  private final SessionLimitProperties sessionLimit;
  private final BreachedPasswordChecker breachedChecker;
  private final TrustedDeviceRepository trustedDevices;
  private final TrustedDeviceProperties trustedDeviceProps;
  private final EmailChangeTokenRepository emailChangeTokens;
  private final EmailChangeMailSender emailChangeMail;

  @Autowired
  public AuthService(UserRepository users, PasswordService passwords, JwtTokenService jwt,
                     TotpService totp, TotpSecretCipher totpCipher,
                     TotpRecoveryCodeRepository recoveryCodes, Clock clock,
                     LoginLockoutProperties lockout, TotpLockoutProperties totpLockout,
                     AuthMetrics metrics, AuditService audit,
                     PasswordPolicyProperties passwordPolicy,
                     EmailVerificationService emailVerification,
                     EmailVerificationProperties emailVerificationProps,
                     PasswordHistoryService passwordHistory,
                     ItemRepository items,
                     LoginHistoryRecorder loginHistory,
                     LoginEventRepository loginEvents,
                     NotificationService notifications,
                     SessionLimitProperties sessionLimit,
                     BreachedPasswordChecker breachedChecker,
                     TrustedDeviceRepository trustedDevices,
                     TrustedDeviceProperties trustedDeviceProps,
                     EmailChangeTokenRepository emailChangeTokens,
                     EmailChangeMailSender emailChangeMail) {
    this.users = users;
    this.passwords = passwords;
    this.jwt = jwt;
    this.totp = totp;
    this.totpCipher = totpCipher;
    this.recoveryCodes = recoveryCodes;
    this.clock = clock;
    this.lockout = lockout;
    this.totpLockout = totpLockout;
    this.metrics = metrics;
    this.audit = audit;
    this.passwordPolicy = passwordPolicy;
    this.emailVerification = emailVerification;
    this.emailVerificationProps = emailVerificationProps;
    this.passwordHistory = passwordHistory;
    this.items = items;
    this.loginHistory = loginHistory;
    this.loginEvents = loginEvents;
    this.notifications = notifications;
    this.sessionLimit = sessionLimit;
    this.breachedChecker = breachedChecker;
    this.trustedDevices = trustedDevices;
    this.trustedDeviceProps = trustedDeviceProps;
    this.emailChangeTokens = emailChangeTokens;
    this.emailChangeMail = emailChangeMail;
  }

  /** Legacy constructor for direct unit tests that do not assert on metrics. */
  public AuthService(UserRepository users, PasswordService passwords, JwtTokenService jwt,
                     TotpService totp, Clock clock, LoginLockoutProperties lockout) {
    this(users, passwords, jwt, totp,
        new TotpSecretCipher(TotpEncryptionKeyStartupCheck.DEV_PLACEHOLDER_KEY),
        null, clock, lockout, new TotpLockoutProperties(),
        AuthMetrics.noop(), AuditService.noop(), new PasswordPolicyProperties(),
        null, new EmailVerificationProperties(), null, null, null, null, null,
        new SessionLimitProperties(), null, null, null, null, null);
  }

  /**
   * Breached-password checker (backlog #197) with the same null-fallback
   * rationale as {@link #passwordPolicy()}: a hand-built instance without
   * the checker bean (the legacy constructor, Mockito {@code @InjectMocks})
   * falls back to the offline local denylist — the production default —
   * so the screen is never silently off. Production always gets the
   * configured bean, shared with {@code PasswordResetService} so all
   * three credential-setting paths enforce identically.
   */
  private BreachedPasswordChecker breachedChecker() {
    return breachedChecker != null ? breachedChecker
        : new LocalDenylistBreachedPasswordChecker();
  }

  /**
   * Email-verification gate (backlog #138) with the same null-fallback
   * rationale as {@link #passwordPolicy()}: a hand-built instance without
   * the verification service (the legacy constructor, Mockito
   * {@code @InjectMocks}) cannot issue or check tokens, so the gate reads
   * as OFF there and registration/login behave exactly as before #138.
   * Production always gets both beans, and the configured default is ON.
   */
  private boolean emailVerificationEnabled() {
    return emailVerification != null && emailVerificationProps != null
        && emailVerificationProps.isEnabled();
  }

  /**
   * Password-policy accessor with a defaults fallback, same rationale as
   * {@link #totpLockout()}: Mockito {@code @InjectMocks} in older unit tests
   * may leave the field null; the documented default (minimum length
   * {@link PasswordStrengthValidator#DEFAULT_MIN_LENGTH}) then applies.
   * Production always gets the configured bean.
   */
  private PasswordPolicyProperties passwordPolicy() {
    return passwordPolicy != null ? passwordPolicy : new PasswordPolicyProperties();
  }

  /**
   * Password-history seam (backlog #139) with the same null-fallback
   * rationale as {@link #passwordPolicy()}: a hand-built instance
   * without the history service (the legacy constructor) falls back to
   * the pre-#139 current-hash-only check, so direct unit tests keep
   * their exact semantics. Production always gets the shared bean —
   * the same bean {@code PasswordResetService} uses, so the change and
   * reset paths enforce identically and cannot drift.
   */
  private void requirePasswordNotReused(User user, String rawNewPassword) {
    if (passwordHistory != null) {
      passwordHistory.requireNotReused(user, rawNewPassword);
    } else if (passwords.matches(rawNewPassword, user.getPasswordHash())) {
      throw new PasswordReuseException(
          "new password must differ from the current password");
    }
  }

  /** Records the outgoing hash on a successful change; see {@link #requirePasswordNotReused}. */
  private void recordOutgoingPassword(User user) {
    if (passwordHistory != null) {
      passwordHistory.recordOutgoing(user);
    }
  }

  /**
   * TOTP-lockout accessor with a defaults fallback, same rationale as
   * {@link #metrics()}: Mockito {@code @InjectMocks} in older unit tests
   * may leave the field null; the documented defaults (enabled, 5
   * attempts, 15 min) then apply. Production always gets the configured
   * bean.
   */
  private TotpLockoutProperties totpLockout() {
    return totpLockout != null ? totpLockout : new TotpLockoutProperties();
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
   * Login-history seam (backlog #171) with the same null-tolerant
   * rationale as {@link #audit()}: a hand-built instance without the
   * recorder (the legacy constructor, Mockito {@code @InjectMocks})
   * records nothing, so direct unit tests keep their exact pre-#171
   * semantics. Production always gets the bean. When present, recording
   * is strict — the recorder's own {@code REQUIRES_NEW} transaction is
   * what lets the row survive the response the attempt produces (see
   * {@link LoginHistoryRecorder}).
   */
  private void recordLogin(Long userIdOrNull, String identifier, LoginOutcome outcome,
                           SessionMeta meta) {
    if (loginHistory != null) {
      loginHistory.record(userIdOrNull, identifier, outcome, meta, clock.instant());
    }
  }

  /**
   * Notification accessor with a no-op fallback, same rationale as
   * {@link #audit()}: a hand-built instance without the notification
   * service (the legacy constructor, Mockito {@code @InjectMocks}) drops
   * the alert instead of failing the login. Production always gets the
   * shared bean.
   */
  private NotificationService notifications() {
    return notifications != null ? notifications : NotificationService.noop();
  }

  /**
   * Session-limit accessor with a defaults fallback, same rationale as
   * {@link #notifications()}: a hand-built instance without the
   * properties bean (the legacy constructor passes explicit defaults;
   * a Mockito {@code @InjectMocks} instance may leave the field null)
   * enforces the documented default cap of 5. Production always gets
   * the configured bean.
   */
  private SessionLimitProperties sessionLimit() {
    return sessionLimit != null ? sessionLimit : new SessionLimitProperties();
  }

  /**
   * Concurrent-session limit with oldest-session eviction (backlog
   * #191). Called AFTER a successful login — the password path of
   * {@link #loginAttempt} or the TOTP exchange {@link
   * #authenticateTotp} — has minted its new token pair, inside that
   * login's own transaction: when the account now holds more live
   * refresh-token families than {@code app.auth.max-sessions-per-user}
   * (default 5), the OLDEST families are revoked until the cap holds
   * again, through the same family-scoped {@link
   * JwtTokenService#revokeFamily} seam as {@link #revokeOtherSessions}
   * — deliberately no {@code tokenVersion} bump, so the evicted
   * sessions' already-issued access tokens live out their remaining
   * &le;15-minute TTL while their refresh cookies 401 at once.
   *
   * <p>Families are ordered by their founding instant
   * ({@code familyIssuedAt}, carried across rotations), with the live
   * row's id as the tie-break for logins minted inside the same clock
   * tick. The just-founded family is never a candidate: a login must
   * not sign itself out, whatever the clock did. Each eviction writes
   * exactly one {@code SESSION_EVICTED} notification inside this
   * transaction (the #157 write discipline — a login that rolls back
   * leaves neither eviction nor row), carrying no IP or device data:
   * what remains signed in is the sessions page's business. A cap
   * below 1 disables enforcement — a misconfigured cap must never
   * sign everybody out.
   */
  private void enforceSessionLimit(Long userId, JwtTokenService.TokenPair pair) {
    int cap = sessionLimit().getMaxSessionsPerUser();
    if (cap < 1 || jwt == null || pair == null || userId == null) {
      return;
    }
    String newFamily = jwt.sessionFamilyOfToken(pair.refreshToken()).orElse(null);
    List<RefreshToken> live = jwt.liveSessionTokens(userId, clock.instant());
    if (live == null) {
      return;
    }
    List<String> familiesOldestFirst = live.stream()
        .sorted(Comparator.comparing(RefreshToken::getFamilyIssuedAt)
            .thenComparing(row -> row.getId() == null ? Long.MAX_VALUE : row.getId()))
        .map(RefreshToken::getFamilyJti)
        .distinct()
        .toList();
    int excess = familiesOldestFirst.size() - cap;
    if (excess <= 0) {
      return;
    }
    List<String> candidates = familiesOldestFirst.stream()
        .filter(family -> !family.equals(newFamily))
        .toList();
    for (String family : candidates.subList(0, Math.min(excess, candidates.size()))) {
      jwt.revokeFamily(family);
      notifications().record(userId, NotificationType.SESSION_EVICTED, null);
    }
  }

  /**
   * New-device login alert (backlog #178). Called on a SUCCESS outcome
   * only — and BEFORE {@link #recordLogin} writes this login's own
   * SUCCESS row, so the comparison set is exactly the account's prior
   * successful logins: the current pair must not match itself.
   *
   * <p>The pair is the (client IP, User-Agent) the login-history capture
   * recorded — the same two values the owner can inspect on the
   * login-history read, and nothing beyond them is stored: the
   * notification row carries only the recipient and the type, no IP and
   * no device string. The account's very first successful login never
   * alerts (there is no prior pair to be new against, and alerting on
   * every signup's first sign-in would train users to ignore it), and a
   * repeat login from a once-new pair matches that pair's SUCCESS row,
   * so each new pair alerts exactly once. Failed logins never reach
   * this method — they are already login-history evidence, and alerting
   * on them would fire for every mistyped password from a new cafe.
   *
   * <p>The row is written through {@link NotificationService#record},
   * so it joins this login's transaction exactly like an order
   * transition's notification (#157): a login that rolls back after
   * this point leaves no alert. Live sessions are not a second
   * comparison source: a session row is created by one of these same
   * logins (or by a refresh of one), so its pair set is a subset of the
   * SUCCESS rows' pair set.
   */
  private void alertIfNewDevice(Long userId, SessionMeta meta) {
    if (loginEvents == null || userId == null) {
      return;
    }
    SessionMeta safeMeta = meta != null ? meta : SessionMeta.unknown();
    if (!loginEvents.existsByUserIdAndOutcome(userId, LoginOutcome.SUCCESS)) {
      return;
    }
    long samePair = loginEvents.countByUserIdAndOutcomeAndDevicePair(
        userId, LoginOutcome.SUCCESS, safeMeta.ipAddress(), safeMeta.userAgent());
    if (samePair == 0) {
      notifications().record(userId, NotificationType.LOGIN_NEW_DEVICE, null);
    }
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

  /**
   * Trusted-device seam (backlog #199) with the same null-tolerant
   * rationale as {@link #recoveryCodesAvailable()}: a hand-built
   * instance without the repository (the legacy constructor) has no
   * trust store, so no login can skip the challenge and no trust can
   * be created there — exactly the pre-#199 behaviour. Production
   * always gets the JPA bean.
   */
  private boolean trustedDevicesAvailable() {
    return trustedDevices != null;
  }

  /** Trusted-device TTL accessor with the documented 30-day default as fallback. */
  private java.time.Duration trustedDeviceTtl() {
    return trustedDeviceProps != null ? trustedDeviceProps.getTtl()
        : java.time.Duration.ofDays(30);
  }

  /** One fresh raw trusted-device token: 32 random bytes, hex — the cookie value. */
  private static String generateTrustedDeviceToken() {
    byte[] bytes = new byte[32];
    new java.security.SecureRandom().nextBytes(bytes);
    return java.util.HexFormat.of().formatHex(bytes);
  }

  /**
   * Creates a trust row for the user (backlog #199) and returns the RAW
   * token for the cookie — the only moment it exists outside the
   * client. Only its SHA-256 hash is stored (Flyway V54).
   */
  private String createTrustedDevice(User user, SessionMeta meta) {
    String raw = generateTrustedDeviceToken();
    SessionMeta safeMeta = meta != null ? meta : SessionMeta.unknown();
    trustedDevices.save(new TrustedDevice(user.getId(),
        JwtTokenService.sha256Hex(raw), safeMeta.userAgent(), safeMeta.ipAddress(),
        clock.instant().plus(trustedDeviceTtl())));
    return raw;
  }

  /**
   * The trusted-device gate at the TOTP branch of login (backlog
   * #199): the presented cookie token is looked up by hash.
   *
   * <ul>
   *   <li>Current hash, this user's row, unexpired → the device is
   *       trusted: the token ROTATES (the presented hash becomes the
   *       row's previous hash, a fresh token becomes current, the
   *       expiry slides) and the fresh raw token is returned for the
   *       new cookie.</li>
   *   <li>Previous (rotated-out) hash → a replay: the row is DELETED
   *       (the cookie may have been copied — neither token works
   *       again) and the login falls through to the challenge.</li>
   *   <li>Expired current hash → the row is deleted and the login
   *       falls through to the challenge.</li>
   *   <li>Anything else (absent, blank, unknown, another user's row)
   *       → no trust; the challenge is issued exactly as before.</li>
   * </ul>
   *
   * @return the fresh raw token to set as the cookie when trust applied
   */
  private java.util.Optional<String> consumeTrustedDevice(User user, String presentedToken,
                                                          SessionMeta meta) {
    if (!trustedDevicesAvailable() || presentedToken == null || presentedToken.isBlank()) {
      return java.util.Optional.empty();
    }
    String hash = JwtTokenService.sha256Hex(presentedToken);
    Instant now = clock.instant();
    java.util.Optional<TrustedDevice> current = trustedDevices.findByTokenHash(hash);
    if (current.isPresent()) {
      TrustedDevice row = current.get();
      if (!row.getUserId().equals(user.getId())) {
        return java.util.Optional.empty();
      }
      if (row.isExpired(now)) {
        trustedDevices.delete(row);
        return java.util.Optional.empty();
      }
      String raw = generateTrustedDeviceToken();
      row.setPreviousTokenHash(row.getTokenHash());
      row.setTokenHash(JwtTokenService.sha256Hex(raw));
      SessionMeta safeMeta = meta != null ? meta : SessionMeta.unknown();
      row.setUserAgent(safeMeta.userAgent());
      row.setIpAddress(safeMeta.ipAddress());
      row.setLastUsedAt(now);
      row.setExpiresAt(now.plus(trustedDeviceTtl()));
      trustedDevices.save(row);
      return java.util.Optional.of(raw);
    }
    // Replay of a rotated-out token: kill that device's trust outright.
    trustedDevices.findByPreviousTokenHash(hash).ifPresent(row -> {
      if (row.getUserId().equals(user.getId())) {
        trustedDevices.delete(row);
      }
    });
    return java.util.Optional.empty();
  }

  /** Retires every trust row of the account (password change, 2FA off, closure). */
  private void revokeAllTrustedDevices(long userId) {
    if (trustedDevicesAvailable()) {
      trustedDevices.deleteByUserId(userId);
    }
  }

  /**
   * Registered user plus their token pair. {@code trustedDeviceToken}
   * (backlog #199) carries the RAW trusted-device token exactly once —
   * on the TOTP exchange that created the trust (opt-in) or on the
   * trusted login that rotated it — so the controller can set the
   * {@code trusted_device} cookie; it is null on every other result
   * and only its SHA-256 hash is ever stored.
   */
  public record AuthResult(User user, JwtTokenService.TokenPair pair,
                           String trustedDeviceToken) {

    /** Every pre-#199 call site: no trusted-device token to hand out. */
    public AuthResult(User user, JwtTokenService.TokenPair pair) {
      this(user, pair, null);
    }
  }

  /**
   * Registration outcome (backlog #138). When the email-verification gate
   * is on, {@code pair} is null and {@code emailVerificationRequired} is
   * true: the account exists but no session was issued — the user must
   * confirm the mailed token and then log in. When the gate is off, this
   * wraps the same user + first token pair registration always issued.
   */
  public record RegisterResult(User user, JwtTokenService.TokenPair pair,
                               boolean emailVerificationRequired) {}

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

  public RegisterResult register(String username, String email, String rawPassword) {
    return register(username, email, rawPassword, SessionMeta.unknown());
  }

  /**
   * Registers a new user. Weak passwords → 400 (see
   * {@link PasswordStrengthValidator}); duplicate username or email → 409.
   * The username and email are stored canonical-lowercase, so the response
   * echoes the canonical form ("Alice" registers as "alice").
   *
   * <p>Email verification (backlog #138): while the gate is enabled
   * ({@code app.auth.email-verification.enabled}, default on) the account
   * is saved unverified, a single-use token is issued through
   * {@link EmailVerificationService} inside this transaction, and NO token
   * pair is created — the result's {@code pair} is null and the controller
   * answers a session-less 201. Only after the address is confirmed can
   * the user log in. With the gate off, registration issues the first
   * token pair exactly as before.
   *
   * @param meta device info captured from the request — recorded on the new
   *     session's refresh-token row (backlog #62)
   */
  @Transactional
  public RegisterResult register(String username, String email, String rawPassword,
                                 SessionMeta meta) {
    PasswordStrengthValidator.requireStrong(rawPassword,
        passwordPolicy().getPasswordMinLength(), breachedChecker());
    String canonicalUsername = canonical(username);
    String canonicalEmail = canonical(email);
    if (users.findByUsernameIgnoreCase(canonicalUsername).isPresent()) {
      throw new DuplicateUserException("username is already taken");
    }
    if (users.findByEmailIgnoreCase(canonicalEmail).isPresent()) {
      throw new DuplicateUserException("email is already registered");
    }
    User user = new User(canonicalUsername, canonicalEmail, passwords.encode(rawPassword));
    if (emailVerificationEnabled()) {
      user.setEmailVerified(false);
      user = users.save(user);
      emailVerification.issue(user);
      return new RegisterResult(user, null, true);
    }
    user = users.save(user);
    return new RegisterResult(user, jwt.createTokenPair(user, meta), false);
  }

  public LoginResult login(String usernameOrEmail, String rawPassword) {
    return login(usernameOrEmail, rawPassword, SessionMeta.unknown());
  }

  /** Login without a trusted-device cookie — see the 4-arg variant (backlog #199). */
  public LoginResult login(String usernameOrEmail, String rawPassword, SessionMeta meta) {
    return login(usernameOrEmail, rawPassword, meta, null);
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
   * <p>Unverified accounts (backlog #138) are the opposite arrangement: the
   * gate is checked only AFTER the password matches, and answers 403
   * ({@code EmailNotVerifiedException}) — credentials may be right, so the
   * credential failure's 401 would lie; and checking before the password
   * would hand the verification state to anyone who knows an address.
   *
   * <p>TOTP two-factor (backlog #78): when the password is correct but the
   * account has 2FA enabled, no token pair is issued — the result is a
   * {@link LoginResult.Challenge} the client exchanges for the pair at
   * {@code POST /api/auth/2fa/authenticate}.
   *
   * @param meta device info captured from the request — recorded on the new
   *     session's refresh-token row (backlog #62)
   * @param trustedDeviceToken the raw {@code trusted_device} cookie value,
   *     if the client presented one (backlog #199): for a TOTP-enabled
   *     account a live trust row skips ONLY this challenge step — the
   *     password above was still verified in full. Absent, unknown,
   *     expired or replayed tokens change nothing: the challenge is
   *     issued exactly as before (a replay also deletes the trust row).
   */
  @Transactional
  public LoginResult login(String usernameOrEmail, String rawPassword, SessionMeta meta,
                           String trustedDeviceToken) {
    // Backlog #153: exactly one auth.login.duration sample per call —
    // success when the call returns (pair or TOTP challenge), failure
    // when it throws. The sample stops inside this transaction,
    // mirroring the counters, which also increment in-transaction.
    Timer.Sample sample = metrics().startLoginTimer();
    try {
      LoginResult result = loginAttempt(usernameOrEmail, rawPassword, meta, trustedDeviceToken);
      metrics().stopLoginTimer(sample, true);
      return result;
    } catch (RuntimeException e) {
      metrics().stopLoginTimer(sample, false);
      throw e;
    }
  }

  private LoginResult loginAttempt(String usernameOrEmail, String rawPassword,
                                   SessionMeta meta, String trustedDeviceToken) {
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
      // Login history (backlog #171): an unknown identifier is recorded
      // with a NULL user id and the identifier exactly as typed — the row
      // is never exposed by the read API (it has no owner), it exists as
      // enumeration-attempt evidence for the retention-bounded table.
      recordLogin(null, usernameOrEmail, LoginOutcome.UNKNOWN_USER, meta);
      throw new InvalidCredentialsException();
    }
    if (user.isDisabled()) {
      // ADMIN-disabled account (backlog #88): the identical 401 as a bad
      // password, checked before the password so a disabled account gives
      // no signal about its password's validity either. Not lockout-tracked
      // — the account is already fully off.
      metrics().loginFailure();
      // Backlog #171: the history folds this into BAD_PASSWORD, exactly
      // like the response — the row is visible only to the account
      // holder, and a distinct value would hand the disabled state a
      // second, greppable name for no reader benefit.
      recordLogin(user.getId(), usernameOrEmail, LoginOutcome.BAD_PASSWORD, meta);
      throw new InvalidCredentialsException();
    }
    Instant now = clock.instant();
    if (lockout.isEnabled()) {
      Instant lockedUntil = user.getLockedUntil();
      if (lockedUntil != null) {
        if (now.isBefore(lockedUntil)) {
          recordLogin(user.getId(), usernameOrEmail, LoginOutcome.LOCKED, meta);
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
          // Backlog #171: the failure that trips the lock is recorded as
          // LOCKED, not BAD_PASSWORD — it is the attempt the lock starts
          // from, and the history should show the lock engaging.
          recordLogin(user.getId(), usernameOrEmail, LoginOutcome.LOCKED, meta);
          throw new AccountLockedException(lockout.getLockDuration().getSeconds());
        }
      }
      recordLogin(user.getId(), usernameOrEmail, LoginOutcome.BAD_PASSWORD, meta);
      throw new InvalidCredentialsException();
    }
    if (emailVerificationEnabled() && !user.isEmailVerified()) {
      // Email verification (backlog #138): the password matched, but the
      // address was never confirmed, so no session and no TOTP challenge
      // is issued. This is a 403 (EmailNotVerifiedException), NOT the 401
      // credential failure — the credentials may be exactly right, and a
      // 401 would send the user into a password-reset loop that fixes
      // nothing. The check sits AFTER the password match on purpose: the
      // verification state leaks only to someone who already holds the
      // password. A wrong password above still counts toward the lockout;
      // this refusal does not (it is not a credential guess).
      recordLogin(user.getId(), usernameOrEmail, LoginOutcome.EMAIL_UNVERIFIED, meta);
      throw new com.toni.marketplace.common.EmailNotVerifiedException();
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
      // Trusted device (backlog #199): the password is verified, and a
      // live trust row for THIS account skips only the TOTP step — the
      // login then completes exactly like the exchange below (same
      // new-device alert, SUCCESS history row, session cap). The token
      // rotated on this use travels on the result so the controller can
      // replace the cookie. No trust (absent / unknown / expired /
      // replayed token) falls through to the challenge unchanged.
      java.util.Optional<String> rotatedTrust =
          consumeTrustedDevice(user, trustedDeviceToken, meta);
      if (rotatedTrust.isPresent()) {
        alertIfNewDevice(user.getId(), meta);
        recordLogin(user.getId(), usernameOrEmail, LoginOutcome.SUCCESS, meta);
        JwtTokenService.TokenPair trustedPair = jwt.createTokenPair(user, meta);
        enforceSessionLimit(user.getId(), trustedPair);
        return new LoginResult.Pair(
            new AuthResult(user, trustedPair, rotatedTrust.get()));
      }
      // Password correct, second factor still outstanding: issue a
      // short-lived signed challenge, never a token pair.
      recordLogin(user.getId(), usernameOrEmail, LoginOutcome.TOTP_REQUIRED, meta);
      Instant expiresAt = now.plus(jwt.getTotpChallengeTtl());
      return new LoginResult.Challenge(jwt.createTotpChallenge(user), expiresAt);
    }
    // Backlog #178: a successful sign-in from a never-seen (IP, device)
    // pair alerts the account holder — checked BEFORE this login's own
    // SUCCESS row is recorded, so the pair cannot match itself.
    alertIfNewDevice(user.getId(), meta);
    recordLogin(user.getId(), usernameOrEmail, LoginOutcome.SUCCESS, meta);
    JwtTokenService.TokenPair pair = jwt.createTokenPair(user, meta);
    // Backlog #191: the new family counts toward the concurrent-session
    // cap — founding it may evict the account's oldest live family.
    enforceSessionLimit(user.getId(), pair);
    return new LoginResult.Pair(new AuthResult(user, pair));
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
   * Turns TOTP 2FA off for the caller (backlog #121) — the reverse of
   * {@link #enableTotp}, which until now had no counterpart: an account
   * that enabled 2FA could never turn it off short of an ADMIN disabling
   * the whole account. Disabling is itself a sensitive transition, so it
   * demands proof of both factors the account has: the current password
   * (knowledge) AND a valid second factor — the current 6-digit TOTP code
   * or an unused recovery code, verified through the same seam as
   * {@link #authenticateTotp} (a recovery code used here is consumed).
   * The password is checked BEFORE the second factor is touched, so a
   * wrong-password attempt can never burn a one-time recovery code.
   *
   * <p>Failures: a wrong password and a wrong code both produce the
   * identical 401 ({@link InvalidCredentialsException}) — no oracle
   * reveals which proof failed. Disabling when 2FA is not enabled is a
   * state conflict, answered 422. Brute-force defence mirrors
   * {@code authenticateTotp} (#101) so this endpoint is not a fresh
   * guessing oracle: an active TOTP lock refuses the attempt outright
   * (423, even for correct proofs), and a genuine second-factor failure
   * feeds the same per-account counter — reaching the ceiling locks the
   * exchange and this endpoint alike. A wrong PASSWORD does not feed the
   * TOTP counter (it is not a second-factor guess, mirroring {@link
   * #changePassword}, whose password proof feeds no counter either), and
   * an undecryptable stored secret is account state, not guessing, and
   * likewise never feeds it.
   *
   * <p>On success, in this one transaction: {@code totpEnabled=false},
   * the secret is cleared, every recovery code is deleted, the TOTP
   * lockout counters are reset, and a {@code TOTP_DISABLED} audit row is
   * appended (actor = target = the account holder, mirroring {@code
   * TOTP_ENABLED}). The current session deliberately survives — no
   * tokenVersion bump, no refresh-family revocation — mirroring enable,
   * which also leaves sessions alone.
   *
   * <p>{@code noRollbackFor}: the lockout counter increment must survive
   * the 401 / 423 it produces, exactly as on {@code authenticateTotp} —
   * the default rollback would erase it and the throttle would never
   * engage. The failure paths mutate nothing else (a recovery code is
   * consumed only on the success path), so committing them is safe.
   */
  @Transactional(noRollbackFor = {InvalidCredentialsException.class,
      AccountLockedException.class})
  public void disableTotp(long userId, String currentPassword, String code) {
    User user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
    if (!user.isTotpEnabled()) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "two-factor authentication is not enabled");
    }
    requireTotpUnlocked(user);
    if (!passwords.matches(currentPassword, user.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }
    verifySecondFactor(user, code);
    user.setTotpEnabled(false);
    user.setTotpSecret(null);
    users.save(user);
    if (recoveryCodesAvailable()) {
      recoveryCodes.deleteByUserId(userId);
    }
    // Backlog #199: with 2FA off there is no challenge left to skip —
    // every trust row is retired with the factor it bypassed.
    revokeAllTrustedDevices(userId);
    // The disable is a sensitive transition — append its audit row
    // inside this transaction (a rollback leaves 2FA on and no row).
    audit().record(userId, AuditAction.TOTP_DISABLED, AuditTargetType.USER, userId);
  }

  /**
   * The #101 lock gate shared by the proof-demanding TOTP management
   * endpoints ({@link #disableTotp}, {@link #regenerateRecoveryCodes}):
   * an active per-account TOTP lock refuses the attempt outright (423,
   * even for correct proofs) — otherwise a management endpoint would
   * bypass the authenticate exchange's lock entirely. The refused
   * attempt does not extend the lock or feed the counter. An expired
   * lock resets the counter, giving the account a fresh allowance.
   * Callers run inside a {@code noRollbackFor} transaction so the reset
   * survives the 401/423 the attempt may go on to produce.
   */
  private void requireTotpUnlocked(User user) {
    TotpLockoutProperties totpLockout = totpLockout();
    if (!totpLockout.isEnabled()) {
      return;
    }
    Instant lockedUntil = user.getTotpLockedUntil();
    if (lockedUntil == null) {
      return;
    }
    Instant now = clock.instant();
    if (now.isBefore(lockedUntil)) {
      throw new AccountLockedException(retryAfterSeconds(now, lockedUntil));
    }
    // Expired lock: the account gets a fresh allowance.
    user.setTotpLockedUntil(null);
    user.setFailedTotpAttempts(0);
  }

  /**
   * The second-factor proof shared by {@link #disableTotp} and {@link
   * #regenerateRecoveryCodes} (factored in backlog #127 so the two
   * endpoints can never drift): the presented value must be a valid
   * 6-digit TOTP code for the stored secret or an unused recovery code
   * (consumed by the success). The caller has already verified the
   * current password, so a wrong-password attempt never reaches this
   * method and can never burn a one-time code.
   *
   * <p>Failures: every failure is the identical 401 ({@link
   * InvalidCredentialsException}) — no oracle reveals which form was
   * expected. Only a genuine code-verification failure feeds the shared
   * #101 counter, and reaching its ceiling locks the exchange and both
   * management endpoints alike (the locking failure itself answers 423,
   * mirroring {@link #authenticateTotp}); an undecryptable stored secret
   * or a missing secret is account state, not guessing, and never feeds
   * it. On success the TOTP lockout counters reset.
   */
  private void verifySecondFactor(User user, String code) {
    long userId = user.getId();
    String stored = user.getTotpSecret();
    if (stored == null) {
      // Enabled flag without a secret is corrupt account state, not a
      // guessing attempt — the identical 401, never lockout-counted.
      throw new InvalidCredentialsException();
    }
    final String secret;
    try {
      secret = resolveTotpSecret(stored);
    } catch (TotpSecretCipher.DecryptionException e) {
      // Tampered/undecryptable storage fails closed as the identical
      // 401 — never an exception leak, never lockout-counted.
      throw new InvalidCredentialsException();
    }
    if (!totp.verify(secret, code)) {
      // Not a TOTP code — try it as a one-time recovery code (#91). A
      // live code is consumed by this proof; a replay, an unknown code
      // and a wrong TOTP code all land on the identical 401 below.
      if (!consumeRecoveryCode(userId, code)) {
        // A genuine second-factor failure — count it on the shared
        // #101 counter, and lock when the ceiling is reached (the
        // locking failure itself answers 423, mirroring authenticate).
        TotpLockoutProperties totpLockout = totpLockout();
        if (totpLockout.isEnabled()) {
          int attempts = user.getFailedTotpAttempts() + 1;
          user.setFailedTotpAttempts(attempts);
          if (attempts >= totpLockout.getMaxAttempts()) {
            user.setTotpLockedUntil(
                clock.instant().plus(totpLockout.getLockDuration()));
            users.save(user);
            throw new AccountLockedException(totpLockout.getLockDuration().getSeconds());
          }
        }
        throw new InvalidCredentialsException();
      }
    }
    // Success: the shared failure counter resets (mirrors the success
    // resets on authenticateTotp and on the old inline disable flow).
    user.setFailedTotpAttempts(0);
    user.setTotpLockedUntil(null);
  }

  /**
   * Regenerates the caller's TOTP recovery-code set without
   * re-enrolling (backlog #127) — the light-weight answer to "I used or
   * lost most of my codes". Until now the only way to get a fresh set
   * was re-running setup + enable (#91), which also rotates the TOTP
   * secret and forces re-enrollment in the authenticator app.
   *
   * <p>The proof bar is exactly {@link #disableTotp}'s, through the
   * shared seam ({@link #requireTotpUnlocked} + {@link
   * #verifySecondFactor}), so the two endpoints cannot drift: the
   * current password AND a valid TOTP code or an unused recovery code
   * (the password is checked first, so a wrong-password attempt never
   * burns a one-time code; a recovery code used as the proof is
   * consumed — moot, since the whole old set is deleted on success).
   * Wrong password and wrong code both produce the identical 401; 2FA
   * not enabled is a state conflict, answered 422; failed code proofs
   * feed the shared #101 lockout and an active lock answers 423.
   *
   * <p>On success, in this one transaction: the old set is deleted, 10
   * fresh codes are issued (returned in display form exactly once —
   * only their SHA-256 hashes are stored, the V21 table as-is), and a
   * {@code RECOVERY_CODES_REGENERATED} audit row is appended (actor =
   * target = the account holder). The TOTP secret and the enabled flag
   * are deliberately untouched — the authenticator app keeps working —
   * and the current session survives, mirroring enable/disable.
   *
   * <p>{@code noRollbackFor}: same rationale as {@link #disableTotp} —
   * the shared lockout counter increment must survive the 401 / 423 it
   * produces; the failure paths mutate nothing else, so committing them
   * is safe.
   *
   * @return the freshly issued recovery codes, display form, shown once
   */
  @Transactional(noRollbackFor = {InvalidCredentialsException.class,
      AccountLockedException.class})
  public List<String> regenerateRecoveryCodes(long userId, String currentPassword,
                                              String code) {
    User user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
    if (!user.isTotpEnabled()) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "two-factor authentication is not enabled");
    }
    requireTotpUnlocked(user);
    if (!passwords.matches(currentPassword, user.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }
    verifySecondFactor(user, code);
    users.save(user);
    List<String> issued = issueRecoveryCodes(userId);
    // The regeneration is a sensitive transition — append its audit
    // row inside this transaction (a rollback keeps the old set valid
    // and writes no row).
    audit().record(userId, AuditAction.RECOVERY_CODES_REGENERATED,
        AuditTargetType.USER, userId);
    return issued;
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
   * <p>Brute-force defences (backlog #101): the endpoint has its own
   * per-IP token bucket in {@code AuthRateLimitFilter} (429 envelope),
   * and failed exchanges feed a per-account counter on the user row
   * (Flyway V23): after {@code app.auth.totp-lockout.max-attempts}
   * consecutive failures the exchange locks for
   * {@code app.auth.totp-lockout.lock-duration} and answers {@link
   * AccountLockedException} (423 + {@code Retry-After}) — even for a
   * correct code under a fresh challenge. A successful exchange resets
   * the counter. The lock is deliberately separate from the #60
   * password lockout (own columns, see {@link TotpLockoutProperties}):
   * a challenge holder cannot lock the owner out of password login —
   * {@link #login} still verifies the password and issues challenges
   * while the exchange is locked; only exchanging them is refused.
   * Only genuine code-verification failures count: a disabled account,
   * a missing enrollment, or undecryptable stored secret are account
   * state, not guessing, and never feed the counter.
   *
   * <p>{@code noRollbackFor}: the failure counter must survive the 401 /
   * 423 it produces — the default rollback-on-RuntimeException would
   * erase the increment (and the lock) with the transaction, so the
   * throttle would never engage outside a caller-managed transaction.
   * The failure paths mutate nothing else (a recovery code is consumed
   * only on the success path), so committing them is safe.
   *
   * @param meta device info captured from the request — recorded on the new
   *     session's refresh-token row (backlog #62)
   */
  @Transactional(noRollbackFor = {InvalidCredentialsException.class,
      AccountLockedException.class})
  public AuthResult authenticateTotp(String challenge, String code, SessionMeta meta) {
    return authenticateTotp(challenge, code, meta, false);
  }

  /**
   * The exchange with the trusted-device opt-in (backlog #199): when
   * {@code rememberDevice} is set, a successful exchange ALSO creates a
   * trust row for this device and the result carries the raw token for
   * the {@code trusted_device} cookie — later password logins from
   * this device skip only this step. The opt-in is honoured only on
   * success and only when the trust store exists; a failed exchange
   * never creates trust.
   */
  @Transactional(noRollbackFor = {InvalidCredentialsException.class,
      AccountLockedException.class})
  public AuthResult authenticateTotp(String challenge, String code, SessionMeta meta,
                                     boolean rememberDevice) {
    long userId = jwt.parseTotpChallenge(challenge);
    // Backlog #171: an unknown user id here means the account vanished
    // between challenge and exchange — there is no username to record
    // the row against, so this one failure leaves no history row.
    User user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
    String stored = user.getTotpSecret();
    if (user.isDisabled() || !user.isTotpEnabled() || stored == null) {
      // Backlog #171: account-state failures fold into the identical 401
      // and into TOTP_FAILED alike (see LoginOutcome).
      recordLogin(user.getId(), user.getUsername(), LoginOutcome.TOTP_FAILED, meta);
      throw new InvalidCredentialsException();
    }
    TotpLockoutProperties totpLockout = totpLockout();
    Instant now = clock.instant();
    if (totpLockout.isEnabled()) {
      Instant totpLockedUntil = user.getTotpLockedUntil();
      if (totpLockedUntil != null) {
        if (now.isBefore(totpLockedUntil)) {
          // Locked: even a correct code under a fresh challenge is
          // refused until the window passes. This attempt does not
          // extend the lock or feed the counter.
          recordLogin(user.getId(), user.getUsername(), LoginOutcome.LOCKED, meta);
          throw new AccountLockedException(retryAfterSeconds(now, totpLockedUntil));
        }
        // Expired lock: the account gets a fresh allowance.
        user.setTotpLockedUntil(null);
        user.setFailedTotpAttempts(0);
      }
    }
    // Backlog #89: resolve the stored secret (decrypt v1:, pass legacy
    // plaintext through). Tampered/undecryptable storage fails closed as
    // the identical 401 — never an exception leak.
    final String secret;
    try {
      secret = resolveTotpSecret(stored);
    } catch (TotpSecretCipher.DecryptionException e) {
      recordLogin(user.getId(), user.getUsername(), LoginOutcome.TOTP_FAILED, meta);
      throw new InvalidCredentialsException();
    }
    if (!totp.verify(secret, code)) {
      // Backlog #91: not a TOTP code — try it as a one-time recovery
      // code. A live code is consumed by this exchange; a replay, an
      // unknown code and a wrong TOTP code all land on the identical
      // 401 below, so the response never reveals which kind was tried.
      if (!consumeRecoveryCode(userId, code)) {
        // Backlog #101: a genuine failed exchange — count it, and lock
        // the exchange when the consecutive-failure ceiling is reached
        // (the locking failure itself answers 423, mirroring #60).
        if (totpLockout.isEnabled()) {
          int attempts = user.getFailedTotpAttempts() + 1;
          user.setFailedTotpAttempts(attempts);
          if (attempts >= totpLockout.getMaxAttempts()) {
            user.setTotpLockedUntil(now.plus(totpLockout.getLockDuration()));
            // Backlog #171: the failure that trips the exchange lock is
            // LOCKED, mirroring the password stage.
            recordLogin(user.getId(), user.getUsername(), LoginOutcome.LOCKED, meta);
            throw new AccountLockedException(totpLockout.getLockDuration().getSeconds());
          }
        }
        recordLogin(user.getId(), user.getUsername(), LoginOutcome.TOTP_FAILED, meta);
        throw new InvalidCredentialsException();
      }
    }
    // Success: the failure counter resets (mirrors the #60 login rule).
    if (totpLockout.isEnabled()
        && (user.getFailedTotpAttempts() != 0 || user.getTotpLockedUntil() != null)) {
      user.setFailedTotpAttempts(0);
      user.setTotpLockedUntil(null);
    }
    if (!cipher().isEncrypted(stored)) {
      // Legacy plaintext row (enabled before #89): re-encrypt in place on
      // this successful authenticate, so plaintext secrets drain out of
      // the table without waiting for a re-enrollment.
      user.setTotpSecret(cipher().encrypt(secret));
      users.save(user);
    }
    // Backlog #178: the TOTP exchange is where this login completes, so
    // the new-device check runs here too — the password stage above only
    // recorded TOTP_REQUIRED, never a SUCCESS. Same before-recording
    // order as on the plain login path.
    alertIfNewDevice(user.getId(), meta);
    recordLogin(user.getId(), user.getUsername(), LoginOutcome.SUCCESS, meta);
    JwtTokenService.TokenPair pair = jwt.createTokenPair(user, meta);
    // Backlog #191: a TOTP-completed login founds a session exactly like
    // the password path, so it counts toward (and can trigger) the cap.
    enforceSessionLimit(user.getId(), pair);
    // Backlog #199: the opt-in creates the trust row inside this same
    // transaction — an exchange that rolls back leaves no trust behind.
    String trustedToken = (rememberDevice && trustedDevicesAvailable())
        ? createTrustedDevice(user, meta) : null;
    return new AuthResult(user, pair, trustedToken);
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
    // Backlog #153: exactly one auth.refresh.duration sample per call —
    // success on a completed rotation, failure when rotation throws.
    Timer.Sample sample = metrics().startRefreshTimer();
    try {
      AuthResult result = refreshAttempt(refreshToken, meta);
      metrics().stopRefreshTimer(sample, true);
      return result;
    } catch (RuntimeException e) {
      metrics().stopRefreshTimer(sample, false);
      throw e;
    }
  }

  private AuthResult refreshAttempt(String refreshToken, SessionMeta meta) {
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
   * leaks no new oracle). Backlog #139 extends the guard through the
   * shared {@link PasswordHistoryService}: a password matching any of
   * the last {@code app.auth.password-history-size} outgoing hashes is
   * likewise 400, and the outgoing hash is appended to the history
   * inside this transaction.
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
    requirePasswordNotReused(user, newPassword);
    PasswordStrengthValidator.requireStrong(newPassword,
        passwordPolicy().getPasswordMinLength(), breachedChecker());
    recordOutgoingPassword(user);
    user.setPasswordHash(passwords.encode(newPassword));
    user.setTokenVersion(user.getTokenVersion() + 1);
    users.save(user);
    jwt.revokeAll(userId);
    // Backlog #199: a password change retires every trusted device —
    // trust was granted against the old credential, so every device
    // pays the full TOTP challenge again until re-trusted.
    revokeAllTrustedDevices(userId);
    metrics().passwordChanged();
    // Backlog #98: append the audit row inside this transaction, so a
    // change that rolls back (e.g. token issuance failing) leaves no row.
    audit().record(userId, AuditAction.PASSWORD_CHANGED, AuditTargetType.USER, userId);
    return new AuthResult(user, jwt.createTokenPair(user, meta));
  }

  /**
   * Requests an email change (backlog #150, reworked by #208). Before
   * #150 no change-email path existed anywhere; #150 flipped the
   * address immediately after the password check — nothing proved the
   * caller controlled the NEW inbox before the switch, so a typo (or a
   * password-only compromise) moved the account's recovery address to
   * a mailbox the owner might not control. #208 splits the flow in
   * two: this method only STAGES the change, and
   * {@link #confirmEmailChange} performs the switch.
   *
   * <p>Proof discipline mirrors {@link #changePassword}: the current
   * password is required and a wrong one produces the identical 401 as
   * a bad login, so no oracle leaks. The new address is canonicalized
   * exactly like registration and uniqueness-checked (taken \u2192 409,
   * the register envelope); re-submitting the account's own current
   * address is likewise a 409 \u2014 it is not a change. Uniqueness is
   * checked again at confirm time (see there): the address may be
   * taken in between.
   *
   * <p>On success, inside this one transaction: {@code pendingEmail}
   * is staged on the user row, any prior unused change token is
   * deleted and a fresh single-use token (32 random bytes, only its
   * SHA-256 hash stored, Flyway V58, the email-verification token TTL)
   * is saved carrying its own copy of the pending address, the raw
   * token is handed to the {@link EmailChangeMailSender} seam
   * addressed to the NEW mailbox, and an
   * {@code EMAIL_CHANGE_REQUESTED} in-app notification is recorded for
   * the account \u2014 visible to the holder at the OLD address, so a
   * hijack attempt is seen before anything moves. Deliberately NOT
   * done here: the address does not flip, the verified flag does not
   * change, no session is revoked, tokenVersion is not bumped and no
   * {@code EMAIL_CHANGED} audit row is written \u2014 all of that belongs
   * to the switch, at confirm. Requesting again replaces the pending
   * row and invalidates the earlier token.
   *
   * <p>Legacy-constructor fallback: a hand-built instance without the
   * token repository or the mail seam (direct unit tests) cannot stage
   * a change, so it performs the pre-#208 immediate switch exactly as
   * #150 did. Production always gets both beans.
   */
  @Transactional
  public void changeEmail(long userId, String currentPassword, String newEmail) {
    User user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
    if (!passwords.matches(currentPassword, user.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }
    String canonicalNewEmail = canonical(newEmail);
    if (canonicalNewEmail.equals(user.getEmail())) {
      throw new DuplicateUserException("new email must differ from the current email");
    }
    if (users.findByEmailIgnoreCase(canonicalNewEmail).isPresent()) {
      throw new DuplicateUserException("email is already registered");
    }
    if (emailChangeTokens == null || emailChangeMail == null) {
      // Legacy-constructor fallback (see the javadoc): the immediate
      // pre-#208 switch, kept only for hand-built unit-test instances.
      user.setEmail(canonicalNewEmail);
      boolean gateOn = emailVerificationEnabled();
      if (gateOn) {
        user.setEmailVerified(false);
      }
      user.setTokenVersion(user.getTokenVersion() + 1);
      users.save(user);
      jwt.revokeAll(userId);
      if (gateOn && emailVerification != null) {
        emailVerification.issue(user);
      }
      audit().record(userId, AuditAction.EMAIL_CHANGED, AuditTargetType.USER, userId);
      return;
    }
    user.setPendingEmail(canonicalNewEmail);
    users.save(user);
    emailChangeTokens.deleteByUserIdAndUsedAtIsNull(userId);
    byte[] raw = new byte[32];
    new java.security.SecureRandom().nextBytes(raw);
    String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    Instant expiresAt = clock.instant().plus(
        emailVerificationProps != null ? emailVerificationProps.getTokenTtl()
            : java.time.Duration.ofHours(24));
    emailChangeTokens.save(new EmailChangeToken(userId, canonicalNewEmail,
        JwtTokenService.sha256Hex(token), expiresAt));
    emailChangeMail.sendEmailChange(user, canonicalNewEmail, token);
    // The alert to the OLD address (see the javadoc): an in-app row
    // for the account holder, recorded inside this transaction.
    notifications().record(userId, NotificationType.EMAIL_CHANGE_REQUESTED, null);
  }

  /**
   * Confirms a pending email change (backlog #208) \u2014 the switch
   * itself. Public: the mailed token IS the credential. Unknown, used
   * and expired tokens all throw the identical
   * {@link InvalidEmailChangeTokenException} (401, mirroring the
   * password-reset confirm's credential semantics).
   *
   * <p>Canonical uniqueness is RE-CHECKED here: the pending address
   * may have been registered by someone else between request and
   * confirm \u2014 that case answers 409 and consumes nothing (the
   * check runs before the token is stamped, and the exception rolls
   * the transaction back), so the holder can retry if the address is
   * freed, or stage a different address instead.
   *
   * <p>On success, inside this one transaction: the address flips to
   * the token's own pending copy, the account is marked VERIFIED (the
   * confirm just proved control of the new inbox \u2014 the #138 flag
   * must not demand a second round trip), the staging field is
   * cleared, the token is consumed and any sibling retired, every
   * refresh family is revoked and tokenVersion is bumped (every
   * pre-switch session dies, mirroring the #150 switch), and exactly
   * one {@code EMAIL_CHANGED} audit row is appended (actor = target =
   * the account holder; the audit table stores no addresses, by
   * design). No fresh pair is issued: the holder logs in at the new
   * address afterwards.
   */
  @Transactional
  public void confirmEmailChange(String rawToken) {
    if (emailChangeTokens == null) {
      throw new InvalidEmailChangeTokenException();
    }
    EmailChangeToken row = emailChangeTokens
        .findByTokenHash(JwtTokenService.sha256Hex(rawToken))
        .orElseThrow(InvalidEmailChangeTokenException::new);
    Instant now = clock.instant();
    if (row.isUsed() || row.isExpired(now)) {
      throw new InvalidEmailChangeTokenException();
    }
    User user = users.findById(row.getUserId())
        .orElseThrow(InvalidEmailChangeTokenException::new);
    String pending = row.getPendingEmail();
    // Re-check (see the javadoc): another account holding the pending
    // address now \u2192 409, token unconsumed. The holder's own current
    // address cannot equal the pending one (the request forbade it),
    // so any hit is necessarily a different account.
    if (users.findByEmailIgnoreCase(pending).isPresent()) {
      throw new DuplicateUserException("email is already registered");
    }
    user.setEmail(pending);
    user.setPendingEmail(null);
    user.setEmailVerified(true);
    user.setTokenVersion(user.getTokenVersion() + 1);
    users.save(user);
    row.setUsedAt(now);
    emailChangeTokens.save(row);
    emailChangeTokens.deleteByUserIdAndUsedAtIsNull(user.getId());
    jwt.revokeAll(user.getId());
    audit().record(user.getId(), AuditAction.EMAIL_CHANGED,
        AuditTargetType.USER, user.getId());
  }

  /**
   * Self-service account closure (backlog #158) — {@code DELETE
   * /api/auth/me}. Before this, no account-deletion path existed anywhere:
   * a user who wanted to leave had no supported exit, and their live
   * sessions and listings had no terminal handling (the ADMIN disable of
   * #88 is staff-side only, and reversible).
   *
   * <p>Proof discipline mirrors {@link #changePassword} plus, for a
   * TOTP-enabled account, {@link #disableTotp}: the current password is
   * required, and a 2FA-enabled account must also present a valid TOTP or
   * recovery code through the shared seam ({@link #requireTotpUnlocked} +
   * {@link #verifySecondFactor}) — the password is checked BEFORE the
   * second factor is touched, so a wrong-password attempt never burns a
   * one-time recovery code, and failed code proofs feed the shared #101
   * lockout (an active lock answers 423). Every proof failure is the
   * identical 401 — no oracle reveals which proof failed.
   *
   * <p>On success, in this one transaction:
   * <ul>
   *   <li>every refresh family is revoked and {@code tokenVersion} is
   *       bumped, so live Bearer tokens die immediately (the #148
   *       semantics);</li>
   *   <li>the caller's AVAILABLE listings flip to {@link
   *       ItemStatus#WITHDRAWN} — RESERVED listings (a live order) and
   *       SOLD listings are untouched, and order history is NEVER
   *       rewritten: PAID/COMPLETED rows keep the seller id for the
   *       counterparty's records;</li>
   *   <li>the user row is ANONYMIZED instead of hard-deleted: username
   *       and email become the {@code deleted-<id>} tombstone (which
   *       frees the originals for re-registration), the password hash is
   *       replaced with the hash of a random UUID nobody knows, the TOTP
   *       secret is cleared, 2FA is switched off, every recovery code is
   *       deleted, and the row is flagged disabled so even the tombstone
   *       credentials answer the identical login 401. A hard DELETE is
   *       impossible without destroying other users' history — order,
   *       message and favorite rows reference this account — so the
   *       tombstone is the design, not a shortcut;</li>
   *   <li>an {@code ACCOUNT_CLOSED} audit row is appended (actor =
   *       target = the account holder).</li>
   * </ul>
   *
   * <p>{@code noRollbackFor}: same rationale as {@link #disableTotp} —
   * the shared lockout counter increment must survive the 401 / 423 it
   * produces; the failure paths mutate nothing else, so committing them
   * is safe.
   */
  @Transactional(noRollbackFor = {InvalidCredentialsException.class,
      AccountLockedException.class})
  public void closeAccount(long userId, String currentPassword, String code) {
    User user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
    if (user.isTotpEnabled()) {
      requireTotpUnlocked(user);
    }
    if (!passwords.matches(currentPassword, user.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }
    if (user.isTotpEnabled()) {
      if (code == null || code.isBlank()) {
        throw new InvalidCredentialsException();
      }
      verifySecondFactor(user, code);
    }
    // Withdraw the caller's live listings — never rewrite order history.
    if (items != null) {
      List<Item> available = items.findBySellerIdAndStatus(userId, ItemStatus.AVAILABLE);
      for (Item item : available) {
        item.setStatus(ItemStatus.WITHDRAWN);
      }
      items.saveAll(available);
    }
    if (recoveryCodesAvailable()) {
      recoveryCodes.deleteByUserId(userId);
    }
    // Backlog #199: the tombstone account has no devices left to trust.
    revokeAllTrustedDevices(userId);
    // Anonymize the row into its tombstone form (see the javadoc above).
    user.setUsername("deleted-" + userId);
    user.setEmail("deleted-" + userId + "@deleted.invalid");
    user.setPasswordHash(passwords.encode(java.util.UUID.randomUUID().toString()));
    user.setTotpSecret(null);
    user.setTotpEnabled(false);
    user.setFailedTotpAttempts(0);
    user.setTotpLockedUntil(null);
    user.setFailedLoginAttempts(0);
    user.setLockedUntil(null);
    user.setDisabled(true);
    user.setTokenVersion(user.getTokenVersion() + 1);
    users.save(user);
    jwt.revokeAll(userId);
    audit().record(userId, AuditAction.ACCOUNT_CLOSED, AuditTargetType.USER, userId);
  }

  /**
   * The step-up proof gate for the account data export (backlog #188),
   * factored here so the export confirm endpoint reuses the exact
   * discipline of {@link #closeAccount} and {@link #disableTotp}
   * instead of growing a third copy: the current password is required,
   * and a TOTP-enabled account must additionally present a valid TOTP
   * or recovery code through the shared seam ({@link
   * #requireTotpUnlocked} + {@link #verifySecondFactor}) — the password
   * is checked BEFORE the second factor is touched, so a
   * wrong-password attempt never burns a one-time recovery code, and
   * failed code proofs feed the shared #101 lockout (an active lock
   * answers 423). Every proof failure is the identical 401 — no oracle
   * reveals which proof failed.
   *
   * <p>{@code noRollbackFor}: same rationale as {@link #disableTotp} —
   * the shared lockout counter increment must survive the 401 / 423 it
   * produces; the failure paths mutate nothing else, so committing
   * them is safe. The caller ({@code DataExportGrantService}) mints
   * the grant only after this method returns, in its own step, so a
   * failed proof never leaves a grant behind.
   */
  @Transactional(noRollbackFor = {InvalidCredentialsException.class,
      AccountLockedException.class})
  public void requireStepUpProof(long userId, String currentPassword, String code) {
    User user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
    if (user.isTotpEnabled()) {
      requireTotpUnlocked(user);
    }
    if (!passwords.matches(currentPassword, user.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }
    if (user.isTotpEnabled()) {
      if (code == null || code.isBlank()) {
        throw new InvalidCredentialsException();
      }
      verifySecondFactor(user, code);
    }
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
   * tokens afterwards, and bumps the row's tokenVersion in the same
   * transaction so every previously issued Bearer token 401s immediately
   * (backlog #148) — before this, a token captured before logout stayed
   * valid for the rest of its 15-minute access TTL even though the user
   * believed they had signed out everywhere. The bump matches logout's
   * already-global semantics (revokeAll kills every family, so no live
   * session is meant to survive) and mirrors password change, password
   * reset and ADMIN disable, which all bump for the same reason. The
   * httpOnly cookie itself is cleared by the controller with an expired
   * {@code Set-Cookie}. A user row that no longer exists skips the bump
   * (its tokens already fail the filter's row lookup) and revokeAll is a
   * no-op, preserving the previous behaviour for that edge.
   */
  @Transactional
  public void logout(long userId) {
    users.findById(userId).ifPresent(user -> {
      user.setTokenVersion(user.getTokenVersion() + 1);
      users.save(user);
    });
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
   * Login history (backlog #171): the caller's own recorded login
   * outcomes, paginated newest first (the controller's
   * {@code @PageableDefault} supplies createdAt/id DESC). Own rows only
   * by construction — the repository query keys on the caller's id, so
   * unknown-identifier rows (NULL {@code user_id}) and other accounts'
   * rows can never appear here. A hand-built instance without the
   * repository (the legacy constructor) reads as an empty page.
   */
  @Transactional(readOnly = true)
  public Page<LoginEventDto> loginHistory(long userId, Pageable pageable) {
    if (loginEvents == null) {
      return Page.empty(pageable);
    }
    return loginEvents.findByUserIdOrderByCreatedAtDesc(userId, pageable)
        .map(LoginEventDto::of);
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
   * Revoke-all-other-sessions (backlog #163): deletes every live
   * refresh-token family of the caller EXCEPT the family behind the
   * presented refresh cookie — the "sign out everywhere else" action the
   * one-at-a-time {@link #revokeSession} list lacked, for a user who spots
   * a suspicious session and wants every other device cut off at once.
   * The current session is identified by the cookie only, never by a
   * client-supplied id, which would let a stolen access token choose
   * which session counts as "current". An absent or unknown cookie
   * identifies no current family, so every family is revoked.
   *
   * <p>Deliberately does NOT bump tokenVersion: the bump is logout's
   * global semantics (backlog #148 — no session is meant to survive a
   * logout), while this action is scoped — the current session's access
   * token must keep working. The honest limit, mirroring #62's caveat:
   * the revoked sessions' already-issued access tokens live out their
   * remaining ≤15-minute TTL; their refresh cookies 401 immediately.
   *
   * @return how many families were revoked (the current one never counts)
   */
  @Transactional
  public int revokeOtherSessions(long userId, String presentedRefreshToken) {
    return revokeOtherSessions(userId, presentedRefreshToken, null);
  }

  /**
   * The #163 action with the trusted-device half (backlog #199): every
   * trust row of the caller is revoked EXCEPT the row behind the
   * presented {@code trusted_device} cookie (matched by its current
   * hash only — a rotated-out token identifies nothing worth keeping).
   * An absent or unknown trusted cookie keeps no trust, mirroring the
   * session rule. The returned count stays the SESSION count of the
   * original contract; trust revocation is a silent side effect, like
   * the session families' own.
   */
  @Transactional
  public int revokeOtherSessions(long userId, String presentedRefreshToken,
                                 String presentedTrustedToken) {
    Instant now = clock.instant();
    String currentFamily = (presentedRefreshToken == null || presentedRefreshToken.isBlank())
        ? null
        : jwt.sessionFamilyOfToken(presentedRefreshToken).orElse(null);
    List<String> otherFamilies = jwt.liveSessionTokens(userId, now).stream()
        .map(RefreshToken::getFamilyJti)
        .distinct()
        .filter(family -> !family.equals(currentFamily))
        .toList();
    otherFamilies.forEach(jwt::revokeFamily);
    if (trustedDevicesAvailable()) {
      String keepHash = (presentedTrustedToken == null || presentedTrustedToken.isBlank())
          ? null : JwtTokenService.sha256Hex(presentedTrustedToken);
      trustedDevices.findByUserIdOrderByCreatedAtAsc(userId).stream()
          .filter(row -> !row.getTokenHash().equals(keepHash))
          .forEach(trustedDevices::delete);
    }
    return otherFamilies.size();
  }

  /**
   * Trusted devices of the caller (backlog #199): the sessions
   * surface's trust list, most recently trusted first. Rows map to
   * {@link TrustedDeviceDto} — never the entity, never a token hash.
   * A hand-built instance without the repository reads as an empty
   * list.
   */
  @Transactional(readOnly = true)
  public List<TrustedDeviceDto> trustedDevices(long userId) {
    if (!trustedDevicesAvailable()) {
      return List.of();
    }
    return trustedDevices.findByUserIdOrderByCreatedAtAsc(userId).stream()
        .map(TrustedDeviceDto::of)
        .sorted(Comparator.comparing(TrustedDeviceDto::createdAt).reversed())
        .toList();
  }

  /**
   * Revokes one trusted device (backlog #199): its row is deleted, so
   * the device's cookie — current and rotated-out tokens alike — buys
   * nothing at the next login. An unknown id — or one belonging to
   * another user — is 404 (no cross-user oracle), mirroring
   * {@link #revokeSession}.
   */
  @Transactional
  public void revokeTrustedDevice(long userId, long deviceId) {
    if (!trustedDevicesAvailable()
        || trustedDevices.findByIdAndUserId(deviceId, userId).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "trusted device not found");
    }
    trustedDevices.deleteById(deviceId);
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
   * ADMIN user list/search (backlog #105): paginated, newest first (the
   * controller's {@code @PageableDefault} supplies createdAt/id DESC; the
   * global max-page-size cap from #75 clamps oversized {@code size}).
   * {@code q} is an optional substring matched case-insensitively over the
   * canonical-lowercase username and email (the #47 normalization); LIKE
   * wildcards in the term are escaped so a literal {@code %} or {@code _}
   * in a search box cannot widen the match. Rows map to
   * {@link AdminUserDto} — never the entity, never a password hash.
   */
  @Transactional(readOnly = true)
  public Page<AdminUserDto> listAdminUsers(String q, Pageable pageable) {
    String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
    if (term.isEmpty()) {
      return users.findAll(pageable).map(AdminUserDto::of);
    }
    String escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return users.searchAdminUsers(escaped, pageable).map(AdminUserDto::of);
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
