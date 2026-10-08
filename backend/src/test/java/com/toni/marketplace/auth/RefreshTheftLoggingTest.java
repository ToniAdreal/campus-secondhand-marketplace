package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.jsonwebtoken.security.Keys;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Backlog #74 — refresh-token theft-detection revocations are WARN-logged.
 * The family-killing path (a revoked token replayed outside the grace
 * window, or an expired token presented) emits exactly one WARN carrying
 * the user id — never the username. The 60 s grace replay and normal
 * rotations stay silent.
 *
 * <p>Pure unit style: repositories are Mockito mocks, time is a
 * {@link MutableClock}; a logback {@code ListAppender} is attached to the
 * {@link JwtTokenService} logger per test and detached afterwards.
 */
class RefreshTheftLoggingTest {

  private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

  private MutableClock clock;
  private RefreshTokenRepository refreshTokens;
  private UserRepository users;
  private JwtTokenService service;
  private User alice;
  private Logger logger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    SecretKey key = Keys.hmacShaKeyFor(bytes);
    clock = new MutableClock(NOW);
    refreshTokens = mock(RefreshTokenRepository.class);
    users = mock(UserRepository.class);
    service = new JwtTokenService(key, Duration.ofMinutes(15), Duration.ofDays(7),
        Duration.ofSeconds(60), Duration.ofDays(30), Duration.ofMinutes(5), refreshTokens, users, clock);

    alice = new User("alice", "alice@example.com", "$2a$12$hashed");
    ReflectionTestUtils.setField(alice, "id", 42L);
    when(users.findById(42L)).thenReturn(Optional.of(alice));

    logger = (Logger) LoggerFactory.getLogger(JwtTokenService.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
  }

  /** Issues a pair and stubs its row lookup; returns the pair. */
  private JwtTokenService.TokenPair issueLivePair() {
    JwtTokenService.TokenPair pair = service.createTokenPair(alice);
    ArgumentCaptor<RefreshToken> created = ArgumentCaptor.forClass(RefreshToken.class);
    verify(refreshTokens).save(created.capture());
    RefreshToken row = created.getValue();
    when(refreshTokens.findByTokenHash(JwtTokenService.sha256Hex(pair.refreshToken())))
        .thenReturn(Optional.of(row));
    return pair;
  }

  @Test
  void theftReplay_outsideGraceWindow_logsOneWarnWithUserIdOnly() {
    JwtTokenService.TokenPair pair = issueLivePair();
    // One legitimate rotation: the old token is now revoked (revokedAt = NOW).
    service.rotateRefreshToken(pair.refreshToken());

    // Replay the revoked token past the 60 s grace window: theft path.
    clock.advance(Duration.ofSeconds(61));
    assertThatThrownBy(() -> service.rotateRefreshToken(pair.refreshToken()))
        .isInstanceOf(InvalidRefreshTokenException.class)
        .hasMessageContaining("session revoked");
    verify(refreshTokens).deleteByUserId(42L);

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    // The user id correlates the revocation; the username must never appear.
    assertThat(event.getFormattedMessage()).contains("id=42");
    assertThat(event.getFormattedMessage()).doesNotContain("alice");
  }

  @Test
  void expiredTokenReplay_logsOneWarn() {
    JwtTokenService.TokenPair pair = issueLivePair();

    // The row-expiry branch: in production the JWT's own exp (second
    // precision) almost always fires first in parse(); this exercises the
    // row-side isExpired check — reachable in the sub-second race between
    // the JWT exp and the row's full-precision expiresAt. Force it directly.
    ArgumentCaptor<RefreshToken> created = ArgumentCaptor.forClass(RefreshToken.class);
    verify(refreshTokens, times(1)).save(created.capture());
    RefreshToken row = created.getAllValues().get(0);
    ReflectionTestUtils.setField(row, "expiresAt", NOW.minusSeconds(5));

    assertThatThrownBy(() -> service.rotateRefreshToken(pair.refreshToken()))
        .isInstanceOf(InvalidRefreshTokenException.class)
        .hasMessageContaining("session revoked");
    verify(refreshTokens).deleteByUserId(42L);

    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    assertThat(appender.list.get(0).getFormattedMessage()).contains("id=42");
  }

  @Test
  void graceReplay_insideWindow_staysSilent() {
    JwtTokenService.TokenPair pair = issueLivePair();
    service.rotateRefreshToken(pair.refreshToken());

    // The live successor row the grace path rotates.
    ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
    verify(refreshTokens, times(3)).save(saved.capture());
    RefreshToken successor = saved.getAllValues().get(2);
    when(refreshTokens.findByJti(successor.getJti())).thenReturn(Optional.of(successor));

    // Replay the just-replaced token inside the 60 s window: graced, no kill.
    clock.advance(Duration.ofSeconds(30));
    JwtTokenService.TokenPair graced = service.rotateRefreshToken(pair.refreshToken());
    assertThat(graced.refreshToken()).isNotBlank();

    verify(refreshTokens, never()).deleteByUserId(any());
    assertThat(appender.list).isEmpty();
  }

  @Test
  void normalRotation_staysSilent() {
    JwtTokenService.TokenPair pair = issueLivePair();

    JwtTokenService.TokenPair rotated = service.rotateRefreshToken(pair.refreshToken());
    assertThat(rotated.refreshToken()).isNotBlank();
    verify(refreshTokens, never()).deleteByUserId(any());

    assertThat(appender.list).isEmpty();
  }
}
