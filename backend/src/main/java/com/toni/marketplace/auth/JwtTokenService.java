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
 *   <li>Access token: 15 min, carries sub (user id), username, roles.</li>
 *   <li>Refresh token: 7 days, single-use, rotating. Each successful rotation
 *       revokes the consumed token and issues a new pair; replaying an old
 *       refresh token revokes the whole family (theft detection).</li>
 *   <li>Only the SHA-256 of a refresh token is stored server-side.</li>
 * </ul>
 *
 * Pure-Java except for the repository — constructed directly in unit tests.
 */
public class JwtTokenService {

  private static final String TYPE_ACCESS = "access";
  private static final String TYPE_REFRESH = "refresh";

  private final SecretKey key;
  private final Duration accessTtl;
  private final Duration refreshTtl;
  private final RefreshTokenRepository refreshTokens;
  private final UserRepository users;
  private final Clock clock;

  public JwtTokenService(SecretKey key, Duration accessTtl, Duration refreshTtl,
                         RefreshTokenRepository refreshTokens, UserRepository users, Clock clock) {
    this.key = key;
    this.accessTtl = accessTtl;
    this.refreshTtl = refreshTtl;
    this.refreshTokens = refreshTokens;
    this.users = users;
    this.clock = clock;
  }

  /** Issued token pair together with absolute expiry instants. */
  public record TokenPair(String accessToken, String refreshToken,
                          Instant accessExpiresAt, Instant refreshExpiresAt) {}

  /** Claims extracted from a validated access token. */
  public record AccessClaims(long userId, String username, List<String> roles) {}

  public TokenPair createTokenPair(User user) {
    Instant now = clock.instant();
    Instant accessExp = now.plus(accessTtl);
    Instant refreshExp = now.plus(refreshTtl);

    List<String> roles = user.getRoles().stream().map(Role::name).sorted().toList();

    String accessToken = Jwts.builder()
        .subject(String.valueOf(user.getId()))
        .claim("username", user.getUsername())
        .claim("roles", roles)
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

    refreshTokens.save(new RefreshToken(sha256Hex(refreshToken), jti, user.getId(), refreshExp));

    return new TokenPair(accessToken, refreshToken, accessExp, refreshExp);
  }

  public AccessClaims parseAccessToken(String token) {
    Claims claims = parse(token);
    requireType(claims, TYPE_ACCESS, "not an access token");
    long userId = Long.parseLong(claims.getSubject());
    String username = claims.get("username", String.class);
    @SuppressWarnings("unchecked")
    List<String> roles = claims.get("roles", List.class);
    return new AccessClaims(userId, username, roles);
  }

  /**
   * Consumes a refresh token and issues a fresh pair (rotation). The old token
   * is revoked. Replaying an already-revoked or unknown token deletes the
   * whole family and raises, so a stolen token cannot be silently reused.
   */
  public TokenPair rotateRefreshToken(String refreshToken) {
    Claims claims = parse(refreshToken);
    requireType(claims, TYPE_REFRESH, "not a refresh token");

    RefreshToken stored = refreshTokens.findByTokenHash(sha256Hex(refreshToken))
        .orElseThrow(() -> {
          // Unknown token: cannot map to a family; reject without side effects.
          return new InvalidRefreshTokenException("unknown refresh token");
        });

    if (stored.isRevoked() || stored.isExpired(clock.instant())) {
      // Possible replay/theft: kill the family, then reject.
      refreshTokens.deleteByUserId(stored.getUserId());
      throw new InvalidRefreshTokenException("refresh token reused or expired; session revoked");
    }

    long userId = Long.parseLong(claims.getSubject());
    if (stored.getUserId() != userId) {
      throw new InvalidRefreshTokenException("refresh token user mismatch");
    }

    Instant now = clock.instant();
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
        .subject(claims.getSubject())
        .claim("type", TYPE_REFRESH)
        .id(newJti)
        .issuedAt(Date.from(now))
        .expiration(Date.from(refreshExp))
        .signWith(key)
        .compact();

    stored.setRevoked(true);
    stored.setReplacedBy(newJti);
    refreshTokens.save(stored);
    refreshTokens.save(new RefreshToken(sha256Hex(newRefreshToken), newJti, userId, refreshExp));

    String newAccessToken = Jwts.builder()
        .subject(claims.getSubject())
        .claim("username", user.getUsername())
        .claim("roles", roles)
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
