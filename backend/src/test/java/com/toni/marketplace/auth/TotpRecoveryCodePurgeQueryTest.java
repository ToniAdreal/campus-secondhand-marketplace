package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

/**
 * DB-level proof of the purge predicate behind backlog #262
 * ({@link TotpRecoveryCodeRepository#findPurgeableIds}): a row is
 * purgeable only when it is used AND created before the retention
 * cut-off. Recovery codes carry no expiry, so there is no
 * expired-unused case — the safety-critical case is the unused code:
 * a live backup credential must never be swept, no matter how generous
 * the cut-off is. Rows are persisted "now" (the entity stamps
 * {@code createdAt} on persist), so the cut-off is moved around them
 * instead of the other way round. Mirrors
 * {@link EmailVerificationTokenPurgeQueryTest} (backlog #152).
 */
@DataJpaTest
class TotpRecoveryCodePurgeQueryTest {

  @Autowired
  private TotpRecoveryCodeRepository codes;

  @Autowired
  private UserRepository users;

  @Autowired
  private TestEntityManager entityManager;

  private static String hash(char c) {
    return String.valueOf(c).repeat(64);
  }

  private Long newUserId(String name) {
    return users.saveAndFlush(
        new User(name, name + "@example.com", "$2a$12$hashed")).getId();
  }

  @Test
  void purgeSelectsUsedRowsButNeverAnUnusedCode() {
    Instant now = Instant.now();
    Long userId = newUserId("recoverypurgeuser");

    TotpRecoveryCode used = codes.saveAndFlush(
        new TotpRecoveryCode(userId, hash('a')));
    used.setUsedAt(now);
    codes.saveAndFlush(used);

    TotpRecoveryCode unused = codes.saveAndFlush(
        new TotpRecoveryCode(userId, hash('b')));

    // A cut-off far in the future puts every row past the retention
    // window, so only the used marker can keep the live code.
    assertThat(codes.findPurgeableIds(now.plus(Duration.ofDays(365)),
        PageRequest.of(0, 500)))
        .containsExactly(used.getId())
        .doesNotContain(unused.getId());
  }

  @Test
  void retentionCutoffKeepsUsedRowsThatAreStillRecent() {
    Instant now = Instant.now();

    TotpRecoveryCode used = codes.saveAndFlush(
        new TotpRecoveryCode(newUserId("recoveryrecentuser"), hash('c')));
    used.setUsedAt(now);
    codes.saveAndFlush(used);

    // The row is used, but it was created after this cut-off (the
    // retention window has not lapsed), so the purge leaves it as
    // security-audit evidence.
    assertThat(codes.findPurgeableIds(now.minus(Duration.ofDays(365)),
        PageRequest.of(0, 500)))
        .isEmpty();
  }

  @Test
  void creationCutoffIsStrictlyBefore() {
    Instant now = Instant.now();

    TotpRecoveryCode used = codes.saveAndFlush(
        new TotpRecoveryCode(newUserId("recoveryboundaryuser"), hash('d')));
    used.setUsedAt(now);
    codes.saveAndFlush(used);

    // A cut-off exactly at the row's creation instant does not select
    // it: the predicate is strictly-before, so the row ages out on a
    // later run once the cut-off has moved past it. The boundary is
    // read back from the database (entityManager.clear + reload): the
    // in-memory Instant carries more precision than the stored
    // timestamp, so comparing against it would test the rounding, not
    // the predicate.
    entityManager.clear();
    Instant createdAt = codes.findById(used.getId()).orElseThrow().getCreatedAt();
    assertThat(codes.findPurgeableIds(createdAt,
        PageRequest.of(0, 500))).isEmpty();
    assertThat(codes.findPurgeableIds(createdAt.plusSeconds(1),
        PageRequest.of(0, 500))).containsExactly(used.getId());
  }
}
