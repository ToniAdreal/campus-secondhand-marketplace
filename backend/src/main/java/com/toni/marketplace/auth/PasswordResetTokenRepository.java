package com.toni.marketplace.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

  Optional<PasswordResetToken> findByTokenHash(String tokenHash);

  /**
   * Invalidates every still-unused token of the user — a fresh reset
   * request supersedes all earlier ones (backlog #90), and a successful
   * confirm retires any sibling that could somehow still be live.
   */
  void deleteByUserIdAndUsedAtIsNull(Long userId);

  /**
   * Ids of rows that are dead — already used, or expired as of {@code now} —
   * and were created before {@code createdBefore}, the retention cut-off
   * computed by the purge job (backlog #102). A still-valid unused token
   * (unused and unexpired) never matches, so a purge can never kill an
   * in-flight reset. Ordered by id so the batch loop can always re-read the
   * first page without skipping rows.
   */
  @Query("select t.id from PasswordResetToken t "
      + "where (t.usedAt is not null or t.expiresAt <= :now) "
      + "and t.createdAt < :createdBefore order by t.id")
  List<Long> findPurgeableIds(Instant now, Instant createdBefore, Pageable page);
}
