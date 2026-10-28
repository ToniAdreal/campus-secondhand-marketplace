package com.toni.marketplace.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TotpRecoveryCodeRepository extends JpaRepository<TotpRecoveryCode, Long> {

  /** Collision pre-check for issuance: a hash must be globally unique. */
  boolean existsByCodeHash(String codeHash);

  /** The live (unused) row for one presented code of one user, if any. */
  Optional<TotpRecoveryCode> findByUserIdAndCodeHashAndUsedAtIsNull(Long userId, String codeHash);

  /** How many of the user's codes are still unused — the count endpoint's whole answer. */
  long countByUserIdAndUsedAtIsNull(Long userId);

  /**
   * Invalidates the user's whole set: re-enrollment (setup + enable)
   * replaces it with a fresh one, and setup alone retires the old set
   * because the old secret — the enrollment the codes belonged to — is
   * already gone at that point.
   */
  void deleteByUserId(Long userId);

  /**
   * Ids of rows that are dead — already used — and were created before
   * {@code createdBefore}, the retention cut-off computed by the purge
   * job (backlog #262). Recovery codes carry no expiry, so there is no
   * expired-unused case: an unused row is a live backup credential and
   * never matches, no matter how old it is. Ordered by id so the batch
   * loop can always re-read the first page without skipping rows.
   */
  @Query("select t.id from TotpRecoveryCode t "
      + "where t.usedAt is not null "
      + "and t.createdAt < :createdBefore order by t.id")
  List<Long> findPurgeableIds(Instant createdBefore, Pageable page);
}
