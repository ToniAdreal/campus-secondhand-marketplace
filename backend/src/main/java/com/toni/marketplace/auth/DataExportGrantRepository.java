package com.toni.marketplace.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DataExportGrantRepository extends JpaRepository<DataExportGrant, Long> {

  Optional<DataExportGrant> findByTokenHash(String tokenHash);

  /** A fresh confirm retires every prior grant of the account (live or spent). */
  @Modifying
  @Transactional
  @Query("delete from DataExportGrant g where g.userId = :userId")
  void deleteByUserId(@Param("userId") Long userId);

  /**
   * Ids of rows that are dead — already used, or expired as of
   * {@code now} — and were created before {@code createdBefore}, the
   * retention cut-off computed by the purge job (backlog #262; mirrors
   * the password-reset purge, backlog #102). A still-valid unused grant
   * (an in-flight export download) never matches. A fresh confirm
   * retires every prior grant of the account, so this query only ever
   * sweeps the account's latest grant once it is spent or expired and
   * the account never re-confirms. Selection reads the time columns
   * only — never the user — so no specific account's grants can be
   * selectively erased. Ordered by id so the batch loop can always
   * re-read the first page without skipping rows.
   */
  @Query("select t.id from DataExportGrant t "
      + "where (t.usedAt is not null or t.expiresAt <= :now) "
      + "and t.createdAt < :createdBefore order by t.id")
  List<Long> findPurgeableIds(Instant now, Instant createdBefore, Pageable page);
}
