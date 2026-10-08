package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toni.marketplace.auth.AuthService.AuthResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Backlog #66 — refresh-token theft detection against a real MySQL 8
 * (Testcontainers). The rotation / grace-window / theft logic (backlog
 * #2, #39) has so far only been proven on H2 (MockMvc slice); this test
 * proves the same state machine on the production database engine, where
 * locking, collation, and transaction-isolation behavior actually differ.
 * Requires Docker; runs in CI via {@code mvn verify -Pintegration}, not
 * in the default unit-test phase.
 *
 * <p>Two tests:
 * <ol>
 *   <li>A deterministic lifecycle: login → legitimate rotation → one
 *       grace-path replay inside the 60 s window → a second replay whose
 *       successor is no longer live → theft detection revokes the family
 *       atomically (zero rows remain — no partial revocation).</li>
 *   <li>A concurrent storm: N threads replay the just-consumed token at
 *       the same instant. Outcomes partition into fresh pairs
 *       (grace-path successes, all distinct) and
 *       {@link InvalidRefreshTokenException} — never a 5xx, never a raw
 *       persistence exception. The family then ends in a coherent state:
 *       either it survived (every issued token still live) or it was
 *       revoked all-or-nothing (every issued token dead, zero rows).
 *       Finally the clock is advanced past the grace window and a
 *       post-window replay is shown to kill whatever is left of the
 *       family.</li>
 * </ol>
 *
 * <p>Honest scope: the storm does <em>not</em> assert "exactly one
 * grace-path success". Under InnoDB's default REPEATABLE READ isolation,
 * several racing transactions can each snapshot-read the successor as
 * live and all rotate it (last writer wins on the row; each mints its
 * own valid successor) — that is the documented trade-off of the grace
 * window, and it is also true on H2. What this test proves is the
 * invariant that matters: concurrent legitimate use never produces a
 * 5xx, and revocation is always all-or-nothing — never a family with
 * half its rows gone.
 */
@Testcontainers
@SpringBootTest
class RefreshTheftDetectionMySqlIT {

  @Container
  @ServiceConnection
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  /** Enough racers to make the loser paths near-certain, cheap enough for CI. */
  private static final int RACERS = 8;

  private static final String PASSWORD = "Str0ng-pass1";

  @Autowired
  private AuthService auth;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private MutableClock clock;

  /**
   * The full rotation/theft/grace lifecycle, proven on real MySQL 8.
   * Mirrors RefreshRotationGraceTest's MockMvc coverage on H2, but the
   * state transitions here go through InnoDB transactions.
   */
  @Test
  void rotationTheftLifecycleOnRealMySql() {
    AuthResult reg = auth.register("theft-lifecycle-it", "theft-lifecycle-it@example.com", PASSWORD);
    long userId = reg.user().getId();
    String t0 = reg.pair().refreshToken();
    String familyJti = jwt.sessionFamilyOfToken(t0).orElseThrow();

    // Legitimate rotation: the consumed token is revoked, a successor minted.
    String t1 = auth.refresh(t0).pair().refreshToken();
    assertThat(t1).isNotEqualTo(t0);

    // Grace path: the just-replaced token is accepted once more inside the window.
    String t2 = auth.refresh(t0).pair().refreshToken();
    assertThat(t2).isNotEqualTo(t1);

    // Second replay: the recorded successor (t1) is no longer live, so this
    // is theft — the whole family is revoked.
    assertThatThrownBy(() -> auth.refresh(t0))
        .isInstanceOf(InvalidRefreshTokenException.class);

    // No partial revocation rows: the theft path deletes every row of the
    // user (deleteByUserId), so the family is entirely gone.
    assertThat(refreshTokens.findByFamilyJti(familyJti)).isEmpty();
    assertThat(refreshTokens.findByUserIdAndRevokedFalseAndExpiresAtAfter(
        userId, clock.instant())).isEmpty();

    // The newest pair died with the family — unknown-token path, no side effects.
    assertThatThrownBy(() -> auth.refresh(t2))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  /**
   * N threads replay a consumed token concurrently, inside the grace
   * window. Every outcome must be a fresh pair or an auth failure —
   * never a 5xx — and the family must end fully alive or fully revoked.
   */
  @Test
  void concurrentReplaysUnderLoad_endInACoherentFamilyState() throws Exception {
    AuthResult reg = auth.register("theft-storm-it", "theft-storm-it@example.com", PASSWORD);
    long userId = reg.user().getId();
    String t0 = reg.pair().refreshToken();
    String familyJti = jwt.sessionFamilyOfToken(t0).orElseThrow();

    // Legitimate rotation first: t0 is now a consumed token inside its grace window.
    auth.refresh(t0);

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(RACERS);
    List<AuthResult> successes = new ArrayList<>();
    List<InvalidRefreshTokenException> failures = new ArrayList<>();
    try {
      List<Future<AuthResult>> futures = new ArrayList<>();
      for (int i = 0; i < RACERS; i++) {
        futures.add(pool.submit(() -> {
          start.await();
          return auth.refresh(t0);
        }));
      }
      start.countDown();

      for (Future<AuthResult> f : futures) {
        try {
          successes.add(f.get(60, TimeUnit.SECONDS));
        } catch (ExecutionException e) {
          // The only legitimate failure is the auth failure — a 5xx or a
          // raw persistence exception here would be a real bug.
          assertThat(e.getCause()).isInstanceOf(InvalidRefreshTokenException.class);
          failures.add((InvalidRefreshTokenException) e.getCause());
        }
      }
    } finally {
      pool.shutdownNow();
    }

    // The grace mechanism demonstrably works under concurrent load on MySQL.
    assertThat(successes).isNotEmpty();
    // Every winner minted its own distinct pair.
    assertThat(successes.stream().map(s -> s.pair().refreshToken()).distinct().count())
        .isEqualTo(successes.size());
    assertThat(successes.size() + failures.size()).isEqualTo(RACERS);

    List<RefreshToken> familyRows = refreshTokens.findByFamilyJti(familyJti);
    if (familyRows.isEmpty()) {
      // A racer saw the successor already revoked and hit the theft path:
      // revocation is all-or-nothing, so every issued pair is now dead.
      for (AuthResult s : successes) {
        assertThatThrownBy(() -> auth.refresh(s.pair().refreshToken()))
            .isInstanceOf(InvalidRefreshTokenException.class);
      }
    } else {
      // The family survived the storm: every grace-issued token is still a
      // live row — no partial revocation.
      for (AuthResult s : successes) {
        RefreshToken row = refreshTokens
            .findByTokenHash(JwtTokenService.sha256Hex(s.pair().refreshToken()))
            .orElseThrow();
        assertThat(row.isRevoked()).isFalse();
        assertThat(row.getUserId()).isEqualTo(userId);
      }
    }

    // Post-window: advancing past the 60 s grace window, a replay of the
    // long-consumed token kills whatever is left of the family.
    clock.advance(Duration.ofSeconds(61));
    assertThatThrownBy(() -> auth.refresh(t0))
        .isInstanceOf(InvalidRefreshTokenException.class);
    assertThat(refreshTokens.findByFamilyJti(familyJti)).isEmpty();
  }
}
