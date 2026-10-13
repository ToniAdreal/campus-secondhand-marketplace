package com.toni.marketplace.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

  Optional<WebhookEvent> findByEventId(String eventId);

  /**
   * ADMIN webhook-event viewer (backlog #156): the optional outcome
   * filter is an exact match — a {@code null} parameter disables it.
   * The JPQL is a fixed string with a bound parameter only; the filter
   * value arrives as an allowlisted enum constant, never as query text.
   */
  @Query("select w from WebhookEvent w where (:outcome is null or w.outcome = :outcome)")
  Page<WebhookEvent> searchWebhookEvents(@Param("outcome") WebhookEvent.Outcome outcome,
                                         Pageable pageable);

  boolean existsByEventId(String eventId);

  long countByOutcome(WebhookEvent.Outcome outcome);

  /**
   * Ids of rows created before {@code createdBefore} — the retention
   * cut-off computed by the nightly purge job (backlog #152). The
   * predicate is {@code created_at} only: never outcome, so the purge
   * cannot selectively erase one specific delivery while keeping its
   * neighbours. Ordered by id so the batch loop can always re-read the
   * first page without skipping rows.
   */
  @Query("select w.id from WebhookEvent w where w.createdAt < :createdBefore order by w.id")
  List<Long> findPurgeableIds(Instant createdBefore, Pageable page);
}
