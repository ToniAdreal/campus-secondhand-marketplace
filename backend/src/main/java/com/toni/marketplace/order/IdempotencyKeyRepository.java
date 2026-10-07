package com.toni.marketplace.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

  Optional<IdempotencyKey> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

  /**
   * Ids of terminal rows (the {@code terminal} statuses, normally
   * {@code COMPLETED}/{@code FAILED}) whose last state transition predates
   * {@code updatedBefore} — the retention cut-off computed by the purge job.
   * {@code IN_PROGRESS} rows are deliberately excluded: an owner may still
   * be working. Ordered by id so the batch loop can always re-read the first
   * page without skipping rows.
   */
  @Query("select k.id from IdempotencyKey k "
      + "where k.status in :terminal and k.updatedAt < :updatedBefore order by k.id")
  List<Long> findTerminalIdsBefore(Set<IdempotencyStatus> terminal, Instant updatedBefore,
      Pageable page);
}
