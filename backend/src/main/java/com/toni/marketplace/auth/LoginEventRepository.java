package com.toni.marketplace.auth;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LoginEventRepository extends JpaRepository<LoginEvent, Long> {

  /**
   * The caller's own login history (backlog #171), newest first. Keyed on
   * {@code userId} alone, so rows recorded against unknown identifiers
   * (NULL {@code user_id}) and every other account's rows are excluded by
   * construction — there is no query shape here that could return them.
   */
  Page<LoginEvent> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

  /**
   * Ids of rows created before {@code createdBefore} — the retention
   * cut-off computed by the nightly purge job (backlog #171). The
   * predicate is {@code created_at} only: never outcome or user, so the
   * purge cannot selectively erase one specific attempt while keeping
   * its neighbours. Ordered by id so the batch loop can always re-read
   * the first page without skipping rows. Mirrors
   * {@code WebhookEventRepository.findPurgeableIds}.
   */
  @Query("select e.id from LoginEvent e where e.createdAt < :createdBefore order by e.id")
  List<Long> findPurgeableIds(Instant createdBefore, Pageable page);
}
