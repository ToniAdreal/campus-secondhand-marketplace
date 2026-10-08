package com.toni.marketplace.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;

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
 *       refresh token revokes the whole family (theft detection).</li>
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
 * </ul>
 *
 * Pure-Java except for the repository — constructed directly in unit tests.
 */
public class JwtTokenService {

  private static final String TYPE_ACCESS = "access";
  private static final String TYPE_REFRESH = "refresh";
  private static final String CLAIM_TOKEN_VERSION = "tver";

  private final SecretKey key;
  private final Duration accessTtl;
  private final Duration refreshTtl;
  private final Duration refreshGraceWindow;
  private final Duration refreshMaxAge;
  private final RefreshTokenRepository refreshTokens;
  private final UserRepository users;
  private final Clock clock;

  public JwtTokenService(SecretKey key, Duration accessTtl, Duration refreshTtl,
                         Duration refreshGraceWindow, Duration refreshMaxAge,
                         RefreshTokenRepository refreshTokens, UserRepository users, Clock clock) {
    this.key = key;
    this.accessTtl = accessTtl;
    this.refreshTtl = refreshTtl;
    this.refreshGraceWindow = refreshGraceWindow;
    this.refreshMaxAge = refreshMaxAge;
    this.refreshTokens = refreshTokens;
    this.users = users;
    this.clock = clock;
  }

  /** Issued token pair together with absolute expiry instants. */
  public record TokenPair(String accessToken, String refreshToken,
                          Instant accessExpiresAt, Instant refreshExpiresAt) {}

  /** Claims extracted from a validated access token. */
  public record AccessClaims(long userId, String username, List<String> roles, long tokenVersion) {}

  public TokenPair createTokenPair(User user) {
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
        .signWith(key)
        .compact();

    String jti = UUID.randomUUID().toString();
    String refreshToken = Jwts.builder()
        .subject(String.valueOf(user.getId()))
        .claim("type", TYPE_REFRESH)
        .id(jti)
        .issuedAt(Date.from(now))
        .expiration(Date.from(refreshExp))
        .signWith(key)
        .compact();

    // The issued token founds a new family: its root is this token itself.
    refreshTokens.save(new RefreshToken(sha256Hex(refreshToken), jti, user.getId(), refreshExp,
        now, jti));

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
      TokenPair graced = tryGraceRotation(stored, claims, now);
      if (graced != null) {
        return graced;
      }
      refreshTokens.deleteByUserId(stored.getUserId());
      throw new InvalidRefreshTokenException("refresh token reused or expired; session revoked");
    }

    if (stored.isExpired(now)) {
      // Expired tokens never qualify for grace; same theft treatment.
      refreshTokens.deleteByUserId(stored.getUserId());
      throw new InvalidRefreshTokenException("refresh token reused or expired; session revoked");
    }

    long userId = Long.parseLong(claims.getSubject());
    if (stored.getUserId() != userId) {
      throw new InvalidRefreshTokenException("refresh token user mismatch");
    }

    return rotateLive(stored, userId, claims.getSubject(), now);
  }

  /**
   * Concurrent-refresh grace: accepts a just-replaced token once more if its
   * {@code replacedBy} successor is still the live token of this chain and
   * the revocation happened inside the grace window. Returns {@code null}
   * when the token does not qualify — the caller then takes the theft path.
   */
  private TokenPair tryGraceRotation(RefreshToken stale, Claims claims, Instant now) {
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
    return rotateLive(successor, userId, claims.getSubject(), now);
  }

  /**
   * Rotates a live (non-revoked, non-expired) stored refresh token: marks it
   * revoked — timestamped so the grace window can be measured — and issues
   * the next pair for the same user.
   */
  private TokenPair rotateLive(RefreshToken live, long userId, String subject, Instant now) {
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
        .signWith(key)
        .compact();

    live.setRevoked(true);
    live.setRevokedAt(now);
    live.setReplacedBy(newJti);
    refreshTokens.save(live);
    // The family root travels with the chain: each rotation inherits the
    // family's issued-at and family jti from the consumed token, so the
    // absolute lifetime is measured from the original login, not the
    // last rotation.
    refreshTokens.save(new RefreshToken(sha256Hex(newRefreshToken), newJti, userId, refreshExp,
        live.getFamilyIssuedAt(), live.getFamilyJti()));

    String newAccessToken = Jwts.builder()
        .subject(subject)
        .claim("username", user.getUsername())
        .claim("roles", roles)
        .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
        .claim("type", TYPE_ACCESS)
        .issuedAt(Date.from(now))
        .expiration(Date.from(accessExp))
        .signWith(key)
        .compact();

    return new TokenPair(newAccessToken, newRefreshToken, accessExp, refreshExp);
  }

  /** Logs out: deletes every refresh token of the user. */
  public void revokeAll(long userId) {
    refreshTokens.deleteByUserId(userId);
  }

  private Claims parse(String token) {
    try {
      return Jwts.parser()
          .verifyWith(key)
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
