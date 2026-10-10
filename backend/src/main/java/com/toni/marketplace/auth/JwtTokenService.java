package com.toni.marketplace.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import com.toni.marketplace.common.InvalidTokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Issues and validates JWT access/refresh tokens (jjwt 0.12.x, HS256).
 *
 * <ul>
 *   <li>Access token: 15 min, carries sub (user id), username, roles — plus a
 *       {@code tver} claim (the user's {@code token_version} at issuance).
 *       The authentication filter compares it against the row on every
 *       request, so a password change (which bumps the row) kills all
 *       pre-change tokens immediately instead of leaving them valid for
 *       their full TTL. Tokens minted before this field existed carry no
 *       claim and parse as 0 — they survive only until the account's next
 *       password change.</li>
 *   <li>Refresh token: 7 days, single-use, rotating. Each successful rotation
 *       revokes the consumed token and issues a new pair; replaying an old
 *       refresh token revokes the whole family (theft detection) and logs
 *       the revocation at WARN with the user id — no usernames in the log
 *       line (ids only; the request id is already in the MDC via the
 *       RequestIdFilter, so the line correlates with access logs).</li>
 *   <li>Concurrent-tab grace (backlog #39): the just-replaced token is
 *       accepted <em>once more</em> inside a short grace window (default
 *       60 s), keyed off the {@code replacedBy} chain — two tabs racing to
 *       refresh no longer kill each other's session. The grace rotates the
 *       live successor (the chain stays linear), so the same stale token
 *       cannot be graced twice; a replay after the window, or whose
 *       successor is no longer live, still kills the family.</li>
 *   <li>Honest trade-off: the grace window weakens theft detection inside
 *       the window. A stolen token replayed within the window races the
 *       legitimate chain (each side keeps rotating the other's successor)
 *       instead of triggering revocation; theft is only caught by replays
 *       outside the window. The window is deliberately short (60 s) to
 *       bound this.</li>
 *   <li>Absolute family lifetime (backlog #61): rotation alone no longer
 *       keeps a session alive forever. A refresh past
 *       {@code refreshMaxAge} (default 30 d) from the original login is
 *       rejected with 401 and the family revoked — even when the presented
 *       token is unexpired — forcing a fresh login. Revocation is scoped to
 *       the family's {@code familyJti}, so the user's other sessions are
 *       untouched (unlike the theft path, which kills by user id).</li>
 *   <li>Only the SHA-256 of a refresh token is stored server-side.</li>
 *   <li>TOTP 2FA challenge (backlog #78): a short-lived signed JWT
 *       ({@code totp-challenge}) minted at login for TOTP-enabled accounts
 *       and exchanged — with a valid 6-digit code — for the real pair. It
 *       carries no privileges and is accepted by exactly one endpoint.</li>
 *   <li>Signing-key rotation (backlog #113): every token is signed with
 *       the current key only, stamped with a {@code kid} header naming it
 *       ({@code app.jwt.current-kid}). During a rotation the previous key
 *       ({@code app.jwt.previous-secret} / {@code previous-kid}) is accepted
 *       for VERIFICATION ONLY, and only while the clock is strictly before
 *       {@code app.jwt.previous-accept-until}; it can never sign (a token
 *       that carries the current kid but was signed with the previous key
 *       fails signature verification like any forgery). An unknown
 *       {@code kid} resolves to no key and fails with the same
 *       {@link InvalidTokenException} → 401 as a bad signature — the kid
 *       is a routing hint, never an oracle. Tokens with no {@code kid}
 *       (minted before #113) are tried against the current key only.
 *       Scope of the window: it covers every signed JWT this service
 *       verifies — access, refresh, and the TOTP challenge — because all
 *       three share {@code parse}. Refresh tokens additionally carry
 *       DB-backed semantics (the stored SHA-256 of the token string,
 *       family revocation, rotation grace, the absolute family cap);
 *       NONE of those depend on the signing key — the stored hash is of
 *       the token text, not of a key — so an old-key refresh token inside
 *       the window rotates normally, and the fresh pair it receives is
 *       signed with the current key, migrating the session off the old
 *       key. After the window, old-key tokens of every kind get the
 *       plain 401 (the refresh family is not revoked by a signature
 *       failure; the session simply can no longer authenticate).</li>
 * </ul>
 *
 * Pure-Java except for the repository — constructed directly in unit tests.
 */
public class JwtTokenService {

  private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);

  private static final String TYPE_ACCESS = "access";
  private static final String TYPE_REFRESH = "refresh";
  private static final String TYPE_TOTP_CHALLENGE = "totp-challenge";
  private static final String CLAIM_TOKEN_VERSION = "tver";

  /** Default {@code kid} for the signing key when none is configured. */
  public static final String DEFAULT_CURRENT_KID = "current";
  /** Default {@code kid} naming the previous (verification-only) key. */
  public static final String DEFAULT_PREVIOUS_KID = "previous";

  private final SecretKey key;
  private final String currentKid;
  private final SecretKey previousKey;
  private final String previousKid;
  private final Instant previousAcceptUntil;
  private final Duration accessTtl;
  private final Duration refreshTtl;
  private final Duration refreshGraceWindow;
  private final Duration refreshMaxAge;
  private final Duration totpChallengeTtl;
  private final RefreshTokenRepository refreshTokens;
  private final UserRepository users;
  private final Clock clock;
  private final AuthMetrics metrics;

  public JwtTokenService(SecretKey key, Duration accessTtl, Duration refreshTtl,
                         Duration refreshGraceWindow, Duration refreshMaxAge,
                         Duration totpChallengeTtl,
                         RefreshTokenRepository refreshTokens, UserRepository users, Clock clock,
                         AuthMetrics metrics) {
    this(key, DEFAULT_CURRENT_KID, null, DEFAULT_PREVIOUS_KID, null,
        accessTtl, refreshTtl, refreshGraceWindow, refreshMaxAge, totpChallengeTtl,
        refreshTokens, users, clock, metrics);
  }

  /**
   * Full constructor with key-rotation configuration (backlog #113):
   * {@code previousKey} (nullable) verifies tokens whose {@code kid} equals
   * {@code previousKid} only while the clock is strictly before
   * {@code previousAcceptUntil}; it is never used for signing.
   */
  public JwtTokenService(SecretKey key, String currentKid,
                         SecretKey previousKey, String previousKid, Instant previousAcceptUntil,
                         Duration accessTtl, Duration refreshTtl,
                         Duration refreshGraceWindow, Duration refreshMaxAge,
                         Duration totpChallengeTtl,
                         RefreshTokenRepository refreshTokens, UserRepository users, Clock clock,
                         AuthMetrics metrics) {
    this.key = key;
    this.currentKid = currentKid == null || currentKid.isBlank() ? DEFAULT_CURRENT_KID : currentKid;
    this.previousKey = previousKey;
    this.previousKid = previousKid == null || previousKid.isBlank()
        ? DEFAULT_PREVIOUS_KID : previousKid;
    this.previousAcceptUntil = previousAcceptUntil;
    this.accessTtl = accessTtl;
    this.refreshTtl = refreshTtl;
    this.refreshGraceWindow = refreshGraceWindow;
    this.refreshMaxAge = refreshMaxAge;
    this.totpChallengeTtl = totpChallengeTtl;
    this.refreshTokens = refreshTokens;
    this.users = users;
    this.clock = clock;
    this.metrics = metrics;
    // Assigned here, not in a field initializer: the locator reads the
    // final key/kid/clock fields above, which a field initializer would
    // capture before the constructor assigns them (compile error).
    this.keyLocator = header -> {
      String kid = header instanceof JwsHeader jws ? jws.getKeyId() : null;
      if (kid == null || kid.equals(this.currentKid)) {
        return this.key;
      }
      if (this.previousKey != null && kid.equals(this.previousKid)
          && this.previousAcceptUntil != null
          && this.clock.instant().isBefore(this.previousAcceptUntil)) {
        return this.previousKey;
      }
      return null;
    };
  }

  /** Legacy constructor for direct unit tests that do not assert on metrics. */
  public JwtTokenService(SecretKey key, Duration accessTtl, Duration refreshTtl,
                         Duration refreshGraceWindow, Duration refreshMaxAge,
                         Duration totpChallengeTtl,
                         RefreshTokenRepository refreshTokens, UserRepository users, Clock clock) {
    this(key, accessTtl, refreshTtl, refreshGraceWindow, refreshMaxAge, totpChallengeTtl,
        refreshTokens, users, clock, AuthMetrics.noop());
  }

  public Duration getTotpChallengeTtl() {
    return totpChallengeTtl;
  }

  /**
   * Mints the short-lived signed challenge for a TOTP-enabled account
   * (backlog #78): {@code POST /api/auth/login} returns it with HTTP 202
   * instead of a token pair, and {@code POST /api/auth/2fa/authenticate}
   * exchanges it (plus a valid 6-digit code) for the real pair. The
   * challenge carries no privileges — it is accepted by exactly one
   * endpoint — and every use still requires a fresh TOTP code, so replay
   * inside the TTL is harmless.
   */
  public String createTotpChallenge(User user) {
    Instant now = clock.instant();
    return Jwts.builder()
        .subject(String.valueOf(user.getId()))
        .claim("type", TYPE_TOTP_CHALLENGE)
        .id(UUID.randomUUID().toString())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(totpChallengeTtl)))
        .header().keyId(currentKid).and().signWith(key)
        .compact();
  }

  /**
   * Validates a 2FA challenge and returns the challenged user id. A wrong
   * type (e.g. an access or refresh token presented here), an expired
   * challenge, or a bad signature all throw {@link InvalidTokenException}
   * → 401.
   */
  public long parseTotpChallenge(String token) {
    Claims claims = parse(token);
    requireType(claims, TYPE_TOTP_CHALLENGE, "not a two-factor challenge");
    return Long.parseLong(claims.getSubject());
  }

  /** Issued token pair together with absolute expiry instants. */
  public record TokenPair(String accessToken, String refreshToken,
                          Instant accessExpiresAt, Instant refreshExpiresAt) {}

  /** Claims extracted from a validated access token. */
  public record AccessClaims(long userId, String username, List<String> roles, long tokenVersion) {}

  public TokenPair createTokenPair(User user) {
    return createTokenPair(user, SessionMeta.unknown());
  }

  /**
   * Issues a token pair and records the session's device info
   * ({@link SessionMeta}) on the new refresh-token row (backlog #62).
   */
  public TokenPair createTokenPair(User user, SessionMeta meta) {
    Instant now = clock.instant();
    Instant accessExp = now.plus(accessTtl);
    Instant refreshExp = now.plus(refreshTtl);

    List<String> roles = user.getRoles().stream().map(Role::name).sorted().toList();

    String accessToken = Jwts.builder()
        .subject(String.valueOf(user.getId()))
        .claim("username", user.getUsername())
        .claim("roles", roles)
        .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
        .claim("type", TYPE_ACCESS)
        .issuedAt(Date.from(now))
        .expiration(Date.from(accessExp))
        .header().keyId(currentKid).and().signWith(key)
        .compact();

    String jti = UUID.randomUUID().toString();
    String refreshToken = Jwts.builder()
        .subject(String.valueOf(user.getId()))
        .claim("type", TYPE_REFRESH)
        .id(jti)
        .issuedAt(Date.from(now))
        .expiration(Date.from(refreshExp))
        .header().keyId(currentKid).and().signWith(key)
        .compact();

    // The issued token founds a new family: its root is this token itself.
    RefreshToken row = new RefreshToken(sha256Hex(refreshToken), jti, user.getId(), refreshExp,
        now, jti);
    row.setUserAgent(meta.userAgent());
    row.setIpAddress(meta.ipAddress());
    refreshTokens.save(row);

    return new TokenPair(accessToken, refreshToken, accessExp, refreshExp);
  }

  public AccessClaims parseAccessToken(String token) {
    Claims claims = parse(token);
    requireType(claims, TYPE_ACCESS, "not an access token");
    long userId = Long.parseLong(claims.getSubject());
    String username = claims.get("username", String.class);
    @SuppressWarnings("unchecked")
    List<String> roles = claims.get("roles", List.class);
    // Tokens minted before the tokenVersion field existed carry no claim —
    // they parse as 0, matching the V10 backfill on existing rows.
    Long tokenVersion = claims.get(CLAIM_TOKEN_VERSION, Long.class);
    return new AccessClaims(userId, username, roles, tokenVersion == null ? 0L : tokenVersion);
  }

  /**
   * Consumes a refresh token and issues a fresh pair (rotation). The old token
   * is revoked. Replaying an already-revoked or unknown token deletes the
   * whole family and raises, so a stolen token cannot be silently reused —
   * except for the concurrent-tab grace: the just-replaced token is accepted
   * once more inside {@link #refreshGraceWindow} (see class javadoc).
   */
  public TokenPair rotateRefreshToken(String refreshToken) {
    return rotateRefreshToken(refreshToken, SessionMeta.unknown());
  }

  /**
   * Rotation variant that records the caller's device info
   * ({@link SessionMeta}) on the newly issued row (backlog #62); when the
   * meta is unknown the new row inherits the consumed row's label.
   */
  public TokenPair rotateRefreshToken(String refreshToken, SessionMeta meta) {
    Claims claims = parse(refreshToken);
    requireType(claims, TYPE_REFRESH, "not a refresh token");

    RefreshToken stored = refreshTokens.findByTokenHash(sha256Hex(refreshToken))
        .orElseThrow(() -> {
          // Unknown token: cannot map to a family; reject without side effects.
          return new InvalidRefreshTokenException("unknown refresh token");
        });

    Instant now = clock.instant();

    // Absolute family lifetime (backlog #61): past the cap the refresh is
    // rejected and the family revoked — even if the presented token is
    // unexpired, revoked-within-grace, or simply old. Family-scoped, so
    // other sessions survive.
    if (!now.isBefore(stored.getFamilyIssuedAt().plus(refreshMaxAge))) {
      refreshTokens.deleteByFamilyJti(stored.getFamilyJti());
      throw new InvalidRefreshTokenException("refresh session lifetime exceeded; login again");
    }

    if (stored.isRevoked() && !stored.isExpired(now)) {
      // Possible legitimate concurrent refresh: two tabs raced each other
      // and the slower tab's refresh arrived with the just-replaced token.
      // Grace it once by rotating the live successor (the chain stays
      // linear, so this exact stale token cannot be graced twice). If the
      // token is past the grace window — or its successor is no longer the
      // live token — treat the replay as theft: kill the family and reject.
      TokenPair graced = tryGraceRotation(stored, claims, now, meta);
      if (graced != null) {
        return graced;
      }
      revokeFamilyOnReuse(stored.getUserId());
      throw new InvalidRefreshTokenException("refresh token reused or expired; session revoked");
    }

    if (stored.isExpired(now)) {
      // Expired tokens never qualify for grace; same theft treatment.
      revokeFamilyOnReuse(stored.getUserId());
      throw new InvalidRefreshTokenException("refresh token reused or expired; session revoked");
    }

    long userId = Long.parseLong(claims.getSubject());
    if (stored.getUserId() != userId) {
      throw new InvalidRefreshTokenException("refresh token user mismatch");
    }

    return rotateLive(stored, userId, claims.getSubject(), now, meta);
  }

  /**
   * Theft-detection revocation (backlog #74): the caller presented a token
   * that is not the live one — a replayed revoked token outside the grace
   * window, or an expired one — so the whole family is killed. The WARN
   * carries the user id only, never the username (PII); the request id is
   * already in the MDC via the RequestIdFilter, so this line correlates
   * with the access logs.
   */
  private void revokeFamilyOnReuse(long userId) {
    log.warn("Revoked all refresh-token sessions for user id={} after refresh-token reuse",
        userId);
    // Counted at the same choke point as the WARN (#79), so the
    // auth.refresh.theft_detected counter and the log line never disagree.
    // (Null-guarded: counting must never break a revocation, e.g. when a
    // test constructs this service without a metrics instance.)
    if (metrics != null) {
      metrics.refreshTheftDetected();
    }
    refreshTokens.deleteByUserId(userId);
  }

  /**
   * Concurrent-refresh grace: accepts a just-replaced token once more if its
   * {@code replacedBy} successor is still the live token of this chain and
   * the revocation happened inside the grace window. Returns {@code null}
   * when the token does not qualify — the caller then takes the theft path.
   */
  private TokenPair tryGraceRotation(RefreshToken stale, Claims claims, Instant now,
                                     SessionMeta meta) {
    String successorJti = stale.getReplacedBy();
    Instant revokedAt = stale.getRevokedAt();
    if (successorJti == null || revokedAt == null) {
      return null;
    }
    if (revokedAt.isBefore(now.minus(refreshGraceWindow))) {
      return null;
    }
    RefreshToken successor = refreshTokens.findByJti(successorJti).orElse(null);
    if (successor == null || successor.isRevoked() || successor.isExpired(now)) {
      return null;
    }
    long userId = Long.parseLong(claims.getSubject());
    if (stale.getUserId() != userId || successor.getUserId() != userId) {
      return null;
    }
    // Rotate the live successor: both tabs end up with a working token and
    // the chain stays linear, spending this stale token's single grace grant.
    return rotateLive(successor, userId, claims.getSubject(), now, meta);
  }

  /**
   * Rotates a live (non-revoked, non-expired) stored refresh token: marks it
   * revoked — timestamped so the grace window can be measured — and issues
   * the next pair for the same user.
   */
  private TokenPair rotateLive(RefreshToken live, long userId, String subject, Instant now,
                               SessionMeta meta) {
    Instant accessExp = now.plus(accessTtl);
    Instant refreshExp = now.plus(refreshTtl);

    // Refresh tokens carry no identity claims; re-read the user so role
    // changes (e.g. promotion to ADMIN) take effect on the next rotation.
    User user = users.findById(userId)
        .orElseThrow(() -> {
          refreshTokens.deleteByUserId(userId);
          return new InvalidRefreshTokenException("user no longer exists; session revoked");
        });
    List<String> roles = user.getRoles().stream().map(Role::name).sorted().toList();

    // Revoke the consumed token, then issue a new pair for the same user.
    String newJti = UUID.randomUUID().toString();
    String newRefreshToken = Jwts.builder()
        .subject(subject)
        .claim("type", TYPE_REFRESH)
        .id(newJti)
        .issuedAt(Date.from(now))
        .expiration(Date.from(refreshExp))
        .header().keyId(currentKid).and().signWith(key)
        .compact();

    live.setRevoked(true);
    live.setRevokedAt(now);
    live.setReplacedBy(newJti);
    refreshTokens.save(live);
    // The family root travels with the chain: each rotation inherits the
    // family's issued-at and family jti from the consumed token, so the
    // absolute lifetime is measured from the original login, not the
    // last rotation.
    RefreshToken next = new RefreshToken(sha256Hex(newRefreshToken), newJti, userId, refreshExp,
        live.getFamilyIssuedAt(), live.getFamilyJti());
    // Device info is captured at login/refresh; when the caller did not
    // supply it (legacy/test path) the new row inherits the consumed row's
    // label so the session keeps a stable device identity.
    next.setUserAgent(meta.userAgent() != null ? meta.userAgent() : live.getUserAgent());
    next.setIpAddress(meta.ipAddress() != null ? meta.ipAddress() : live.getIpAddress());
    refreshTokens.save(next);

    String newAccessToken = Jwts.builder()
        .subject(subject)
        .claim("username", user.getUsername())
        .claim("roles", roles)
        .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
        .claim("type", TYPE_ACCESS)
        .issuedAt(Date.from(now))
        .expiration(Date.from(accessExp))
        .header().keyId(currentKid).and().signWith(key)
        .compact();

    return new TokenPair(newAccessToken, newRefreshToken, accessExp, refreshExp);
  }

  /** Logs out: deletes every refresh token of the user. */
  public void revokeAll(long userId) {
    refreshTokens.deleteByUserId(userId);
  }

  /**
   * Per-session management (backlog #62): the user's live sessions, one row
   * per refresh-token family (the live — non-revoked, unexpired — row of
   * each chain).
   */
  public List<RefreshToken> liveSessionTokens(long userId, Instant now) {
    return refreshTokens.findByUserIdAndRevokedFalseAndExpiresAtAfter(userId, now);
  }

  /**
   * The family jti of the session a presented refresh token belongs to —
   * used to flag the caller's current session. Any row state counts (even a
   * revoked row maps to its family), so a just-rotated cookie still flags
   * the right session.
   */
  public Optional<String> sessionFamilyOfToken(String refreshToken) {
    return refreshTokens.findByTokenHash(sha256Hex(refreshToken)).map(RefreshToken::getFamilyJti);
  }

  /**
   * Whether every row of the family belongs to {@code userId}. A family id
   * that does not exist at all reads as not-owned, so unknown ids and other
   * users' ids are indistinguishable to the caller (no cross-user oracle).
   */
  public boolean familyBelongsToUser(long userId, String familyJti) {
    List<RefreshToken> rows = refreshTokens.findByFamilyJti(familyJti);
    return !rows.isEmpty() && rows.stream().allMatch(r -> r.getUserId() == userId);
  }

  /** Revokes one session's family without touching the user's other sessions. */
  public void revokeFamily(String familyJti) {
    refreshTokens.deleteByFamilyJti(familyJti);
  }

  /**
   * Key routing for verification (backlog #113): the token's {@code kid}
   * header picks the key — the current kid gets the signing key; the
   * previous kid gets the previous key only inside the rotation window
   * (strictly before {@code previousAcceptUntil}); a missing kid falls
   * back to the current key (pre-#113 tokens). Anything else resolves to
   * no key, and jjwt's failure surfaces as the same
   * {@link InvalidTokenException} → 401 as a bad signature.
   */
  private final Locator<Key> keyLocator;

  private Claims parse(String token) {
    try {
      return Jwts.parser()
          .keyLocator(keyLocator)
          .clock(() -> Date.from(clock.instant()))
          .build()
          .parseSignedClaims(token)
          .getPayload();
    } catch (JwtException | IllegalArgumentException e) {
      throw new InvalidTokenException("invalid token: " + e.getMessage(), e);
    }
  }

  private static void requireType(Claims claims, String expected, String message) {
    if (!expected.equals(claims.get("type", String.class))) {
      throw new InvalidTokenException(message);
    }
  }

  static String sha256Hex(String input) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** Builds the HS256 key from a base64 secret; rejects short keys. */
  public static SecretKey keyFromBase64(String base64Secret) {
    byte[] bytes = java.util.Base64.getDecoder().decode(base64Secret);
    if (bytes.length < 32) {
      throw new IllegalArgumentException("JWT secret must decode to at least 256 bits");
    }
    return Keys.hmacShaKeyFor(bytes);
  }
}
