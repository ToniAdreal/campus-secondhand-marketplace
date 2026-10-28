package com.toni.marketplace.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Persistence for {@link PasskeyChallenge} (backlog #220). Reads are
 * keyed on the challenge hash (the verify path) or scoped to one user's
 * one purpose (the options path's replace-prior-unused discipline and
 * account-lifecycle cleanup).
 */
public interface PasskeyChallengeRepository extends JpaRepository<PasskeyChallenge, Long> {

  Optional<PasskeyChallenge> findByChallengeHash(String challengeHash);

  List<PasskeyChallenge> findByUserIdAndPurposeAndUsedAtIsNull(long userId,
      PasskeyChallengePurpose purpose);

  void deleteByUserIdAndPurposeAndUsedAtIsNull(long userId, PasskeyChallengePurpose purpose);

  void deleteByUserId(long userId);

  /**
   * Ids of rows that are dead — already used (a verify attempt stamps
   * {@code usedAt} on success or failure), or expired as of
   * {@code now} — and were created before {@code createdBefore}, the
   * retention cut-off computed by the purge job (backlog #262). A
   * still-valid unused challenge (an in-flight ceremony) never matches,
   * so a purge can never kill it. The per-purpose replacement on the
   * options path only retires rows for that user and purpose; this
   * query is what sweeps the expired rows of accounts that never call
   * options again. Selection reads the time columns only — never the
   * user or the purpose — so no specific account's challenges can be
   * selectively erased. Ordered by id so the batch loop can always
   * re-read the first page without skipping rows.
   */
  @Query("select t.id from PasskeyChallenge t "
      + "where (t.usedAt is not null or t.expiresAt <= :now) "
      + "and t.createdAt < :createdBefore order by t.id")
  List<Long> findPurgeableIds(Instant now, Instant createdBefore, Pageable page);
}
