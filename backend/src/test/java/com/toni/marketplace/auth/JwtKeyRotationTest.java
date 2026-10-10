package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toni.marketplace.common.InvalidTokenException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Backlog #113 — JWT signing-key rotation. Pure unit tests in the
 * {@link JwtTokenServiceTest} style: mocked repositories, a fixed clock.
 *
 * <p>Setup mirrors a real rotation: an "old" deployment signed with
 * {@code oldKey} under kid {@code v1}; the "new" deployment signs with
 * {@code newKey} under kid {@code v2} and — for a bounded window only —
 * still verifies {@code v1} tokens with the old key.
 */
class JwtKeyRotationTest {

  private static final Instant NOW = Instant.parse("2026-10-10T05:00:00Z");
  private static final String OLD_KID = "v1";
  private static final String NEW_KID = "v2";

  private SecretKey oldKey;
  private SecretKey newKey;
  private RefreshTokenRepository refreshTokens;
  private UserRepository users;
  private User alice;

  private static SecretKey randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Keys.hmacShaKeyFor(bytes);
  }

  @BeforeEach
  void setUp() {
    oldKey = randomKey();
    newKey = randomKey();
    refreshTokens = mock(RefreshTokenRepository.class);
    users = mock(UserRepository.class);
    alice = new User("alice", "alice@example.com", "$2a$12$hashed");
    ReflectionTestUtils.setField(alice, "id", 42L);
  }

  /** The pre-rotation deployment: signs and verifies with the old key/kid. */
  private JwtTokenService oldService() {
    return new JwtTokenService(oldKey, OLD_KID, null, OLD_KID, null,
        Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofSeconds(60),
        Duration.ofDays(30), Duration.ofMinutes(5),
        refreshTokens, users, Clock.fixed(NOW, ZoneOffset.UTC), AuthMetrics.noop());
  }

  /** The post-rotation deployment, accepting the old kid until {@code acceptUntil}. */
  private JwtTokenService newService(Instant acceptUntil) {
    return new JwtTokenService(newKey, NEW_KID, oldKey, OLD_KID, acceptUntil,
        Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofSeconds(60),
        Duration.ofDays(30), Duration.ofMinutes(5),
        refreshTokens, users, Clock.fixed(NOW, ZoneOffset.UTC), AuthMetrics.noop());
  }

  /** Manually mints an access token — long expiry so window checks are isolated from TTL expiry. */
  private static String mintAccess(SecretKey signingKey, String kid, Instant expiresAt) {
    var builder = Jwts.builder()
        .subject("42")
        .claim("username", "alice")
        .claim("roles", List.of("USER"))
        .claim("type", "access")
        .issuedAt(Date.from(NOW.minus(Duration.ofMinutes(5))))
        .expiration(Date.from(expiresAt));
    if (kid == null) {
      return builder.signWith(signingKey).compact();
    }
    return builder.header().keyId(kid).and().signWith(signingKey).compact();
  }

  private static String headerKid(String token) {
    String headerJson = new String(
        Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
    return headerJson;
  }

  @Test
  void newSigningsCarryTheCurrentKid() {
    JwtTokenService.TokenPair pair = newService(NOW.plus(Duration.ofDays(7))).createTokenPair(alice);

    assertThat(headerKid(pair.accessToken())).contains("\"kid\":\"" + NEW_KID + "\"");
    assertThat(headerKid(pair.refreshToken())).contains("\"kid\":\"" + NEW_KID + "\"");
    // And the new deployment verifies its own tokens.
    assertThat(newService(NOW.plus(Duration.ofDays(7)))
        .parseAccessToken(pair.accessToken()).userId()).isEqualTo(42L);
  }

  @Test
  void oldKeyTokenVerifiesInsideTheWindow() {
    String oldToken = mintAccess(oldKey, OLD_KID, NOW.plus(Duration.ofDays(1)));

    JwtTokenService.AccessClaims claims =
        newService(NOW.plus(Duration.ofHours(1))).parseAccessToken(oldToken);

    assertThat(claims.userId()).isEqualTo(42L);
    assertThat(claims.username()).isEqualTo("alice");
  }

  @Test
  void oldKeyTokenRejectedAfterTheWindow() {
    String oldToken = mintAccess(oldKey, OLD_KID, NOW.plus(Duration.ofDays(1)));

    // Window ended an hour ago: the token itself is nowhere near expiry,
    // so the rejection is the window's, not the TTL's.
    assertThatThrownBy(() -> newService(NOW.minus(Duration.ofHours(1))).parseAccessToken(oldToken))
        .isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void windowEndIsExclusive() {
    String oldToken = mintAccess(oldKey, OLD_KID, NOW.plus(Duration.ofDays(1)));

    // Clock exactly at the deadline: strictly-before semantics reject.
    assertThatThrownBy(() -> newService(NOW).parseAccessToken(oldToken))
        .isInstanceOf(InvalidTokenException.class);
    // One second of window left: accepted.
    assertThat(newService(NOW.plusSeconds(1)).parseAccessToken(oldToken).userId())
        .isEqualTo(42L);
  }

  @Test
  void previousSecretWithoutDeadlineIsNeverAccepted() {
    String oldToken = mintAccess(oldKey, OLD_KID, NOW.plus(Duration.ofDays(1)));

    assertThatThrownBy(() -> newService(null).parseAccessToken(oldToken))
        .isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void unknownKidGetsTheSame401AsABadSignature() {
    String forged = mintAccess(randomKey(), "v99", NOW.plus(Duration.ofDays(1)));

    assertThatThrownBy(() -> newService(NOW.plus(Duration.ofDays(7))).parseAccessToken(forged))
        .isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void previousKeyCannotSignUnderTheCurrentKid() {
    // An attacker (or a misconfiguration) presenting the OLD key's
    // signature under the NEW kid must fail: the locator routes the
    // current kid to the current key only.
    String forged = mintAccess(oldKey, NEW_KID, NOW.plus(Duration.ofDays(1)));

    assertThatThrownBy(() -> newService(NOW.plus(Duration.ofDays(7))).parseAccessToken(forged))
        .isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void legacyTokenWithoutKidVerifiesAgainstTheCurrentKeyOnly() {
    String legacy = mintAccess(newKey, null, NOW.plus(Duration.ofDays(1)));
    assertThat(newService(NOW.plus(Duration.ofDays(7))).parseAccessToken(legacy).userId())
        .isEqualTo(42L);

    // A kid-less token signed with the old key does NOT fall back to the
    // previous key — pre-#113 tokens were all signed with what is now the
    // current key of their deployment, never silently with two keys.
    String legacyOldKey = mintAccess(oldKey, null, NOW.plus(Duration.ofDays(1)));
    assertThatThrownBy(() -> newService(NOW.plus(Duration.ofDays(7))).parseAccessToken(legacyOldKey))
        .isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void oldServiceTokensVerifyOnTheNewService_pairMintedBeforeRotation() {
    // End-to-end at the service level: a pair minted by the old deployment
    // (kid v1 on both tokens) parses on the new deployment in-window.
    JwtTokenService.TokenPair pair = oldService().createTokenPair(alice);

    assertThat(headerKid(pair.accessToken())).contains("\"kid\":\"" + OLD_KID + "\"");
    assertThat(newService(NOW.plus(Duration.ofDays(7))).parseAccessToken(pair.accessToken())
        .userId()).isEqualTo(42L);
  }

  @Test
  void oldRefreshTokenRotatesInsideTheWindowAndMigratesToTheCurrentKid() {
    JwtTokenService.TokenPair pair = oldService().createTokenPair(alice);
    RefreshToken stored = new RefreshToken(
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L,
        NOW.plus(Duration.ofDays(7)), NOW, "family-jti");
    when(refreshTokens.findByTokenHash(stored.getTokenHash())).thenReturn(Optional.of(stored));
    when(users.findById(42L)).thenReturn(Optional.of(alice));

    JwtTokenService.TokenPair rotated =
        newService(NOW.plus(Duration.ofDays(7))).rotateRefreshToken(pair.refreshToken());

    // The session survives rotation — and the fresh pair is signed with
    // the CURRENT key/kid, migrating the session off the old key.
    assertThat(headerKid(rotated.accessToken())).contains("\"kid\":\"" + NEW_KID + "\"");
    assertThat(headerKid(rotated.refreshToken())).contains("\"kid\":\"" + NEW_KID + "\"");
    assertThat(stored.isRevoked()).isTrue();
  }

  @Test
  void oldRefreshTokenAfterTheWindowIsRejected() {
    JwtTokenService.TokenPair pair = oldService().createTokenPair(alice);

    // The signature check fails before any repository lookup, so the
    // family is neither rotated nor theft-revoked — it just cannot
    // authenticate any more (see the JwtTokenService javadoc).
    assertThatThrownBy(() -> newService(NOW.minus(Duration.ofSeconds(1)))
        .rotateRefreshToken(pair.refreshToken()))
        .isInstanceOf(InvalidTokenException.class);
  }
}
