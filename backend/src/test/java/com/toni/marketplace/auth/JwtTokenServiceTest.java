package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.toni.marketplace.common.InvalidTokenException;

/**
 * Pure unit tests for the token lifecycle — repositories are Mockito mocks,
 * time is a fixed clock. No Spring context, no database.
 */
class JwtTokenServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-06T01:40:00Z");

  private SecretKey key;
  private RefreshTokenRepository refreshTokens;
  private UserRepository users;
  private JwtTokenService service;
  private AuthMetrics authMetrics;
  private JwtTokenService metricsService;
  private User alice;

  @BeforeEach
  void setUp() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    key = Keys.hmacShaKeyFor(bytes);
    refreshTokens = mock(RefreshTokenRepository.class);
    users = mock(UserRepository.class);
    service = new JwtTokenService(key, Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofSeconds(60), Duration.ofDays(30), Duration.ofMinutes(5),
        refreshTokens, users, Clock.fixed(NOW, ZoneOffset.UTC));
    // Backlog #212: a second instance wired to a mocked AuthMetrics, for
    // the theft-detection counter assertions the noop-metrics instance
    // above cannot make.
    authMetrics = mock(AuthMetrics.class);
    metricsService = new JwtTokenService(key, Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofSeconds(60), Duration.ofDays(30), Duration.ofMinutes(5),
        refreshTokens, users, Clock.fixed(NOW, ZoneOffset.UTC), authMetrics);

    alice = new User("alice", "alice@example.com", "$2a$12$hashed");
    ReflectionTestUtils.setField(alice, "id", 42L);
  }

  @Test
  void createTokenPair_issuesParseableTokensAndStoresHash() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);

    JwtTokenService.AccessClaims claims = service.parseAccessToken(pair.accessToken());
    assertThat(claims.userId()).isEqualTo(42L);
    assertThat(claims.username()).isEqualTo("alice");
    assertThat(claims.roles()).containsExactly("USER");
    assertThat(pair.accessExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    assertThat(pair.refreshExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));

    // Only the SHA-256 of the refresh token is stored — never the raw token.
    ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
    verify(refreshTokens).save(captor.capture());
    RefreshToken stored = captor.getValue();
    assertThat(stored.getTokenHash()).isEqualTo(JwtTokenService.sha256Hex(pair.refreshToken()));
    assertThat(stored.getTokenHash()).hasSize(64);
    assertThat(stored.isRevoked()).isFalse();
    assertThat(stored.getUserId()).isEqualTo(42L);
  }

  @Test
  void parseAccessToken_rejectsRefreshToken() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);

    assertThatThrownBy(() -> service.parseAccessToken(pair.refreshToken()))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessageContaining("not an access token");
  }

  @Test
  void createTokenPair_embedsTokenVersionInAccessClaims() {
    alice.setTokenVersion(3);
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);

    JwtTokenService.AccessClaims claims = service.parseAccessToken(pair.accessToken());
    assertThat(claims.tokenVersion()).isEqualTo(3);
  }

  @Test
  void parseAccessToken_defaultsMissingVersionClaimToZero() {
    // Tokens minted before the tver claim existed carry no version — they
    // parse as 0, matching the V10 backfill so pre-release sessions survive
    // until their next password change.
    String legacyToken = Jwts.builder()
        .subject("42")
        .claim("username", "alice")
        .claim("roles", List.of("USER"))
        .claim("type", "access")
        .issuedAt(Date.from(NOW))
        .expiration(Date.from(NOW.plus(Duration.ofMinutes(15))))
        .signWith(key)
        .compact();

    JwtTokenService.AccessClaims claims = service.parseAccessToken(legacyToken);
    assertThat(claims.userId()).isEqualTo(42L);
    assertThat(claims.tokenVersion()).isEqualTo(0L);
  }

  @Test
  void parseAccessToken_rejectsTamperedToken() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    String tampered = pair.accessToken().substring(0, pair.accessToken().length() - 2) + "xx";

    assertThatThrownBy(() -> service.parseAccessToken(tampered))
        .isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void parseAccessToken_rejectsExpiredToken() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    JwtTokenService later = new JwtTokenService(key, Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofSeconds(60), Duration.ofDays(30), Duration.ofMinutes(5),
        refreshTokens, users, Clock.fixed(NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC));

    assertThatThrownBy(() -> later.parseAccessToken(pair.accessToken()))
        .isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void rotate_happyPath_revokesOldAndIssuesNewPair() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    RefreshToken oldRow = new RefreshToken(
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L, NOW.plus(Duration.ofDays(7)), NOW, "family-jti");
    when(refreshTokens.findByTokenHash(oldRow.getTokenHash()))
        .thenReturn(Optional.of(oldRow));
    when(users.findById(42L)).thenReturn(Optional.of(alice));

    JwtTokenService.TokenPair rotated = service.rotateRefreshToken(pair.refreshToken());

    // Refresh tokens embed a random jti, so rotation always yields a new one.
    // (The access token is identical here only because the clock is fixed —
    // same instant and claims produce the same signed JWT.)
    assertThat(rotated.refreshToken()).isNotEqualTo(pair.refreshToken());

    // Old row revoked and linked to its replacement.
    assertThat(oldRow.isRevoked()).isTrue();
    assertThat(oldRow.getReplacedBy()).isNotNull();

    // A fresh refresh row was stored (createTokenPair saved 1 row, rotate saves 2).
    ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
    verify(refreshTokens, org.mockito.Mockito.times(3)).save(captor.capture());
    RefreshToken newRow = captor.getAllValues().get(2);
    assertThat(newRow.getJti()).isEqualTo(oldRow.getReplacedBy());
    assertThat(newRow.isRevoked()).isFalse();

    // New access token carries the user's live identity (re-read from the repo).
    JwtTokenService.AccessClaims claims = service.parseAccessToken(rotated.accessToken());
    assertThat(claims.userId()).isEqualTo(42L);
    assertThat(claims.username()).isEqualTo("alice");
  }

  @Test
  void rotate_unknownToken_rejectedWithoutSideEffects() {
    when(refreshTokens.findByTokenHash(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.rotateRefreshToken(service.createTokenPair(alice).refreshToken()))
        .isInstanceOf(InvalidRefreshTokenException.class);
    verify(refreshTokens, never()).deleteByUserId(any());
  }

  @Test
  void rotate_revokedToken_revokesWholeFamily() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    RefreshToken oldRow = new RefreshToken(
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L, NOW.plus(Duration.ofDays(7)), NOW, "family-jti");
    oldRow.setRevoked(true); // replay of an already-consumed token: possible theft
    when(refreshTokens.findByTokenHash(oldRow.getTokenHash()))
        .thenReturn(Optional.of(oldRow));

    assertThatThrownBy(() -> service.rotateRefreshToken(pair.refreshToken()))
        .isInstanceOf(InvalidRefreshTokenException.class)
        .hasMessageContaining("revoked");
    verify(refreshTokens).deleteByUserId(42L);
  }

  @Test
  void rotate_expiredToken_revokesWholeFamily() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    RefreshToken oldRow = new RefreshToken(
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L, NOW.minusSeconds(1),
        NOW, "family-jti");
    when(refreshTokens.findByTokenHash(oldRow.getTokenHash()))
        .thenReturn(Optional.of(oldRow));

    assertThatThrownBy(() -> service.rotateRefreshToken(pair.refreshToken()))
        .isInstanceOf(InvalidRefreshTokenException.class);
    verify(refreshTokens).deleteByUserId(42L);
  }

  @Test
  void rotate_deletedUser_revokesFamily() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    RefreshToken oldRow = new RefreshToken(
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L, NOW.plus(Duration.ofDays(7)), NOW, "family-jti");
    when(refreshTokens.findByTokenHash(oldRow.getTokenHash()))
        .thenReturn(Optional.of(oldRow));
    when(users.findById(42L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.rotateRefreshToken(pair.refreshToken()))
        .isInstanceOf(InvalidRefreshTokenException.class);
    verify(refreshTokens).deleteByUserId(42L);
  }

  @Test
  void revokeAll_deletesFamily() {
    service.revokeAll(42L);

    verify(refreshTokens).deleteByUserId(42L);
  }

  @Test
  void keyFromBase64_rejectsShortKey() {
    assertThatThrownBy(() ->
        JwtTokenService.keyFromBase64(java.util.Base64.getEncoder().encodeToString(new byte[16])))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- Backlog #61: absolute family lifetime ---------------------------------

  @Test
  void createTokenPair_recordsFamilyRoot() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);

    ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
    verify(refreshTokens).save(captor.capture());
    RefreshToken stored = captor.getValue();
    // The issued token founds its own family: root jti is its own jti,
    // issued-at is the issuance instant.
    assertThat(stored.getFamilyJti()).isEqualTo(stored.getJti());
    assertThat(stored.getFamilyIssuedAt()).isEqualTo(NOW);
  }

  @Test
  void rotate_carriesFamilyFieldsForward() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    RefreshToken oldRow = new RefreshToken(
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L,
        NOW.plus(Duration.ofDays(7)), NOW.minus(Duration.ofDays(5)), "root-family");
    when(refreshTokens.findByTokenHash(oldRow.getTokenHash()))
        .thenReturn(Optional.of(oldRow));
    when(users.findById(42L)).thenReturn(Optional.of(alice));

    service.rotateRefreshToken(pair.refreshToken());

    // createTokenPair saved 1 row, rotate saves 2 (consumed + fresh).
    ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
    verify(refreshTokens, org.mockito.Mockito.times(3)).save(captor.capture());
    RefreshToken newRow = captor.getAllValues().get(2);
    assertThat(newRow.getFamilyIssuedAt()).isEqualTo(NOW.minus(Duration.ofDays(5)));
    assertThat(newRow.getFamilyJti()).isEqualTo("root-family");
  }

  @Test
  void rotate_pastFamilyCap_revokesOnlyThatFamily() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    // Token itself is unexpired (7 d), but the family is 31 days old —
    // past the 30-day absolute cap.
    RefreshToken oldRow = new RefreshToken(
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L,
        NOW.plus(Duration.ofDays(7)), NOW.minus(Duration.ofDays(31)), "root-family");
    when(refreshTokens.findByTokenHash(oldRow.getTokenHash()))
        .thenReturn(Optional.of(oldRow));

    assertThatThrownBy(() -> service.rotateRefreshToken(pair.refreshToken()))
        .isInstanceOf(InvalidRefreshTokenException.class)
        .hasMessageContaining("lifetime exceeded");
    // Family-scoped revocation — the user's OTHER sessions must survive,
    // so this is deliberately NOT deleteByUserId.
    verify(refreshTokens).deleteByFamilyJti("root-family");
    verify(refreshTokens, never()).deleteByUserId(any());
  }

  @Test
  void rotate_exactlyAtCap_rejected() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    RefreshToken oldRow = new RefreshToken(
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L,
        NOW.plus(Duration.ofDays(7)), NOW.minus(Duration.ofDays(30)), "root-family");
    when(refreshTokens.findByTokenHash(oldRow.getTokenHash()))
        .thenReturn(Optional.of(oldRow));

    // The cap is an absolute lifetime: now == familyIssuedAt + maxAge is
    // already expired (valid only while now is strictly before the cap).
    assertThatThrownBy(() -> service.rotateRefreshToken(pair.refreshToken()))
        .isInstanceOf(InvalidRefreshTokenException.class)
        .hasMessageContaining("lifetime exceeded");
    verify(refreshTokens).deleteByFamilyJti("root-family");
  }

  // --- Backlog #212: mutation-survivor kill tests ---
  //
  // Targeted at named SURVIVED mutants from PIT run 39735286602 (see
  // docs/mutation.md). PIT cannot run in this sandbox, so the kills are
  // confirmed by the next CI backend-mutation run, not claimed here.

  @Test
  void createTokenPair_explicitSessionMeta_isStoredOnRefreshRow() {
    service.createTokenPair(alice, new SessionMeta("ua-x", "10.0.0.1"));

    ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
    verify(refreshTokens).save(captor.capture());
    assertThat(captor.getValue().getUserAgent()).isEqualTo("ua-x");
    assertThat(captor.getValue().getIpAddress()).isEqualTo("10.0.0.1");
  }

  @Test
  void rotate_unknownMeta_newRowInheritsConsumedRowsDeviceInfo() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    RefreshToken oldRow = new RefreshToken(
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L, NOW.plus(Duration.ofDays(7)), NOW, "family-jti");
    oldRow.setUserAgent("ua-old");
    oldRow.setIpAddress("192.168.0.1");
    when(refreshTokens.findByTokenHash(oldRow.getTokenHash()))
        .thenReturn(Optional.of(oldRow));
    when(users.findById(42L)).thenReturn(Optional.of(alice));

    service.rotateRefreshToken(pair.refreshToken());

    assertThat(oldRow.getRevokedAt()).isEqualTo(NOW);
    ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
    verify(refreshTokens, org.mockito.Mockito.times(3)).save(captor.capture());
    RefreshToken newRow = captor.getAllValues().get(2);
    assertThat(newRow.getUserAgent()).isEqualTo("ua-old");
    assertThat(newRow.getIpAddress()).isEqualTo("192.168.0.1");
  }

  @Test
  void rotate_explicitMeta_newRowCarriesNewDeviceInfo() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    RefreshToken oldRow = new RefreshToken(
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L, NOW.plus(Duration.ofDays(7)), NOW, "family-jti");
    oldRow.setUserAgent("ua-old");
    oldRow.setIpAddress("192.168.0.1");
    when(refreshTokens.findByTokenHash(oldRow.getTokenHash()))
        .thenReturn(Optional.of(oldRow));
    when(users.findById(42L)).thenReturn(Optional.of(alice));

    service.rotateRefreshToken(pair.refreshToken(), new SessionMeta("ua-new", "10.9.9.9"));

    assertThat(oldRow.getRevokedAt()).isEqualTo(NOW);
    ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
    verify(refreshTokens, org.mockito.Mockito.times(3)).save(captor.capture());
    RefreshToken newRow = captor.getAllValues().get(2);
    assertThat(newRow.getUserAgent()).isEqualTo("ua-new");
    assertThat(newRow.getIpAddress()).isEqualTo("10.9.9.9");
  }

  @Test
  void rotate_revokedTokenReuse_countsTheftDetection() {
    JwtTokenService.TokenPair pair = metricsService.createTokenPair(alice);
    RefreshToken oldRow = new RefreshToken(
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L, NOW.plus(Duration.ofDays(7)), NOW, "family-jti");
    oldRow.setRevoked(true); // replay of an already-consumed token: possible theft
    when(refreshTokens.findByTokenHash(oldRow.getTokenHash()))
        .thenReturn(Optional.of(oldRow));

    assertThatThrownBy(() -> metricsService.rotateRefreshToken(pair.refreshToken()))
        .isInstanceOf(InvalidRefreshTokenException.class)
        .hasMessageContaining("revoked");
    // The theft counter moves at the same choke point as the family kill.
    verify(authMetrics).refreshTheftDetected();
    verify(refreshTokens).deleteByUserId(42L);
  }

  @Test
  void rotate_accessToken_throwsNotARefreshToken() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);

    // requireType must reject an access token before any repository work:
    // the message (not just the exception type, which the unknown-token
    // path shares via the subclass) is what pins the type check.
    assertThatThrownBy(() -> service.rotateRefreshToken(pair.accessToken()))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessageContaining("not a refresh token");
  }

  @Test
  void rotate_revokedToken_withLiveUser_stillRejectedAsReuse() {
    // rotateRefreshToken L330 is the revoked-and-unexpired branch: with
    // the user lookup stubbed to succeed, a fall-through past that branch
    // would happily rotate the revoked token instead of rejecting it as
    // reuse — so success of the user lookup is what makes this test pin
    // the branch (the older revoked test's unstubbed lookup masks it).
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    RefreshToken oldRow = new RefreshToken(
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L, NOW.plus(Duration.ofDays(7)), NOW, "family-jti");
    oldRow.setRevoked(true);
    when(refreshTokens.findByTokenHash(oldRow.getTokenHash()))
        .thenReturn(Optional.of(oldRow));
    when(users.findById(42L)).thenReturn(Optional.of(alice));

    assertThatThrownBy(() -> service.rotateRefreshToken(pair.refreshToken()))
        .isInstanceOf(InvalidRefreshTokenException.class)
        .hasMessageContaining("reused or expired");
    verify(refreshTokens).deleteByUserId(42L);
  }

  @Test
  void keyFromBase64_exactly32ByteSecret_isAccepted() {
    // Boundary: the minimum is inclusive — a secret decoding to exactly
    // 32 bytes (256 bits) yields a usable HS256 key.
    byte[] exact = new byte[32];
    for (int i = 0; i < exact.length; i++) {
      exact[i] = (byte) (i + 1);
    }
    SecretKey fromSecret = JwtTokenService.keyFromBase64(
        java.util.Base64.getEncoder().encodeToString(exact));

    assertThat(fromSecret.getEncoded()).hasSize(32);
    assertThat(fromSecret.getAlgorithm()).isEqualTo("HmacSHA256");
  }

  @Test
  void rotate_justReplacedTokenInsideGrace_rotatesLiveSuccessor() {
    // tryGraceRotation, built unit-style (the MockMvc grace suite is
    // Spring-backed; this suite mirrors its scenario against mocks):
    // a just-replaced token replayed inside the 60 s grace window is
    // accepted once more by rotating its still-live successor.
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    RefreshToken stale = new RefreshToken(
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L,
        NOW.plus(Duration.ofDays(7)), NOW, "family-jti");
    stale.setRevoked(true);
    stale.setRevokedAt(NOW);
    stale.setReplacedBy("succ-jti");
    RefreshToken successor = new RefreshToken(
        "successor-hash", "succ-jti", 42L, NOW.plus(Duration.ofDays(7)), NOW, "family-jti");
    when(refreshTokens.findByTokenHash(stale.getTokenHash()))
        .thenReturn(Optional.of(stale));
    when(refreshTokens.findByJti("succ-jti")).thenReturn(Optional.of(successor));
    when(users.findById(42L)).thenReturn(Optional.of(alice));

    JwtTokenService.TokenPair graced = service.rotateRefreshToken(pair.refreshToken());

    assertThat(graced.refreshToken()).isNotEqualTo(pair.refreshToken());
    // The live successor is the row that got consumed by the grace rotation.
    assertThat(successor.isRevoked()).isTrue();
    assertThat(successor.getRevokedAt()).isEqualTo(NOW);
    verify(refreshTokens, never()).deleteByUserId(any());
  }

  @Test
  void rotate_withinFamilyCap_succeeds() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    // 29 days old, token unexpired: inside the cap, rotation proceeds.
    RefreshToken oldRow = new RefreshToken(
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L,
        NOW.plus(Duration.ofDays(7)), NOW.minus(Duration.ofDays(29)), "root-family");
    when(refreshTokens.findByTokenHash(oldRow.getTokenHash()))
        .thenReturn(Optional.of(oldRow));
    when(users.findById(42L)).thenReturn(Optional.of(alice));

    JwtTokenService.TokenPair rotated = service.rotateRefreshToken(pair.refreshToken());

    assertThat(rotated.refreshToken()).isNotEqualTo(pair.refreshToken());
    verify(refreshTokens, never()).deleteByFamilyJti(any());
  }
}
