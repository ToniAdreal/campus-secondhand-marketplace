package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.security.Keys;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

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
  private User alice;

  @BeforeEach
  void setUp() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    key = Keys.hmacShaKeyFor(bytes);
    refreshTokens = mock(RefreshTokenRepository.class);
    users = mock(UserRepository.class);
    service = new JwtTokenService(key, Duration.ofMinutes(15), Duration.ofDays(7),
        refreshTokens, users, Clock.fixed(NOW, ZoneOffset.UTC));

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
  void parseAccessToken_rejectsTamperedToken() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    String tampered = pair.accessToken().substring(0, pair.accessToken().length() - 2) + "xx";

    assertThatThrownBy(() -> service.parseAccessToken(tampered))
        .isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void parseAccessToken_rejectsExpiredToken() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    JwtTokenService later = new JwtTokenService(key, Duration.ofMinutes(15), Duration.ofDays(7),
        refreshTokens, users, Clock.fixed(NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC));

    assertThatThrownBy(() -> later.parseAccessToken(pair.accessToken()))
        .isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void rotate_happyPath_revokesOldAndIssuesNewPair() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    RefreshToken oldRow = new RefreshToken(
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L, NOW.plus(Duration.ofDays(7)));
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
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L, NOW.plus(Duration.ofDays(7)));
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
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L, NOW.minusSeconds(1));
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
        JwtTokenService.sha256Hex(pair.refreshToken()), "old-jti", 42L, NOW.plus(Duration.ofDays(7)));
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
}
