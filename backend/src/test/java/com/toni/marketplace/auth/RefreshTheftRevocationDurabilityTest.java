package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Backlog #386 — refresh-token theft revocation must survive its own
 * exception. {@link JwtTokenService#rotateRefreshToken} deletes the
 * family and then throws {@link InvalidRefreshTokenException}; when
 * the delete runs inside {@link AuthService#refresh}'s transaction,
 * the default rollback-on-{@link RuntimeException} undoes the delete,
 * so the "revoked" family lives on with every row intact.
 *
 * <p>The H2 suite never caught this because
 * {@link RefreshRotationGraceTest} and {@link RefreshFamilyLifetimeTest}
 * are {@code @Transactional}: they read the uncommitted delete inside
 * the test's own transaction and see zero rows, while the real MySQL
 * *IT ({@code RefreshTheftDetectionMySqlIT}) — no outer transaction —
 * watches the service transaction roll the delete back. This class is
 * deliberately NOT {@code @Transactional}, mirroring the *IT: the
 * service transaction must commit for real, so a rolled-back
 * revocation fails here on H2, in the unit phase, with no Docker.
 */
@SpringBootTest
class RefreshTheftRevocationDurabilityTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final String PASSWORD = "Str0ng-pass1";

  @Autowired
  private AuthService auth;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private UserRepository users;

  @Autowired
  private MutableClock clock;

  private final List<Long> createdUserIds = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (Long userId : createdUserIds) {
      refreshTokens.deleteByUserId(userId);
      users.deleteById(userId);
    }
    createdUserIds.clear();
  }

  private AuthService.RegisterResult register() {
    String tag = "theft-durability-" + UUID.randomUUID().toString().substring(0, 8);
    AuthService.RegisterResult reg = auth.register(tag, tag + "@example.com", PASSWORD);
    createdUserIds.add(reg.user().getId());
    return reg;
  }

  @Test
  void secondReplayOfStaleTokenDeletesFamilyRows() {
    AuthService.RegisterResult reg = register();
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

    // The revocation must be durable: the theft path's delete survives the
    // exception that follows it (no partial revocation rows).
    assertThat(refreshTokens.findByFamilyJti(familyJti)).isEmpty();
    assertThat(refreshTokens.findByUserIdAndRevokedFalseAndExpiresAtAfter(
        userId, clock.instant())).isEmpty();

    // The newest pair died with the family — unknown-token path, no side effects.
    assertThatThrownBy(() -> auth.refresh(t2))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void replayAfterGraceWindowDeletesFamilyRows() {
    AuthService.RegisterResult reg = register();
    String t0 = reg.pair().refreshToken();
    String familyJti = jwt.sessionFamilyOfToken(t0).orElseThrow();

    auth.refresh(t0);
    clock.advance(Duration.ofSeconds(61));

    // Past the window the replay is indistinguishable from theft.
    assertThatThrownBy(() -> auth.refresh(t0))
        .isInstanceOf(InvalidRefreshTokenException.class);

    assertThat(refreshTokens.findByFamilyJti(familyJti)).isEmpty();
  }
}
