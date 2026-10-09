package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

/**
 * DB-level proof of the purge predicate behind backlog #102
 * ({@link PasswordResetTokenRepository#findPurgeableIds}): a row is
 * purgeable only when it is dead — used, or expired as of {@code now} —
 * AND created before the retention cut-off. The safety-critical case is
 * the still-valid unused token: an in-flight reset must never be swept,
 * no matter how generous the cut-off is. Rows are persisted "now" (the
 * entity stamps {@code createdAt} on persist), so the cut-off is moved
 * around them instead of the other way round.
 */
@DataJpaTest
class PasswordResetTokenPurgeQueryTest {

  @Autowired
  private PasswordResetTokenRepository tokens;

  @Autowired
  private UserRepository users;

  private static String hash(char c) {
    return String.valueOf(c).repeat(64);
  }

  private Long newUserId(String name) {
    return users.saveAndFlush(
        new User(name, name + "@example.com", "$2a$12$hashed")).getId();
  }

  @Test
  void purgeSelectsUsedAndExpiredRowsButNeverAStillValidUnusedToken() {
    Instant now = Instant.now();
    Long userId = newUserId("purgeuser");

    PasswordResetToken used = tokens.saveAndFlush(
        new PasswordResetToken(userId, hash('a'), now.plus(Duration.ofDays(1))));
    used.setUsedAt(now);
    tokens.saveAndFlush(used);

    PasswordResetToken expiredUnused = tokens.saveAndFlush(
        new PasswordResetToken(userId, hash('b'), now.minus(Duration.ofDays(1))));

    PasswordResetToken stillValidUnused = tokens.saveAndFlush(
        new PasswordResetToken(userId, hash('c'), now.plus(Duration.ofDays(1))));

    // A cut-off far in the future puts every row past the retention
    // window, so only the dead-row predicate can keep the live token.
    assertThat(tokens.findPurgeableIds(now, now.plus(Duration.ofDays(365)),
        PageRequest.of(0, 500)))
        .containsExactlyInAnyOrder(used.getId(), expiredUnused.getId())
        .doesNotContain(stillValidUnused.getId());
  }

  @Test
  void retentionCutoffKeepsDeadRowsThatAreStillRecent() {
    Instant now = Instant.now();

    PasswordResetToken used = tokens.saveAndFlush(
        new PasswordResetToken(newUserId("recentuser"), hash('d'),
            now.plus(Duration.ofDays(1))));
    used.setUsedAt(now);
    tokens.saveAndFlush(used);

    // The row is dead, but it was created after this cut-off (the
    // retention window has not lapsed), so the purge leaves it as
    // security-audit evidence.
    assertThat(tokens.findPurgeableIds(now, now.minus(Duration.ofDays(365)),
        PageRequest.of(0, 500)))
        .isEmpty();
  }
}
