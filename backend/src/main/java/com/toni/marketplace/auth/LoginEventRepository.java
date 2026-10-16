package com.toni.marketplace.auth;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginEventRepository extends JpaRepository<LoginEvent, Long> {

  /**
   * The caller's own login history (backlog #171), newest first. Keyed on
   * {@code userId} alone, so rows recorded against unknown identifiers
   * (NULL {@code user_id}) and every other account's rows are excluded by
   * construction — there is no query shape here that could return them.
   */
  Page<LoginEvent> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

  /**
   * Whether the account has any recorded login with the given outcome
   * (backlog #178): the new-device check first asks this for
   * {@link LoginOutcome#SUCCESS} — an account with no prior successful
   * login has nothing to compare a device against, so its very first
   * login can never be "new".
   */
  boolean existsByUserIdAndOutcome(Long userId, LoginOutcome outcome);

  /**
   * How many of the account's recorded logins with the given outcome
   * carry exactly this (IP, User-Agent) pair (backlog #178). NULL-safe
   * on both columns: a capture with no User-Agent sent stores NULL, and
   * two such captures are the same pair. Written as an explicit query
   * because a derived query cannot express the NULL-equality.
   */
  @Query("select count(e) from LoginEvent e where e.userId = :userId"
      + " and e.outcome = :outcome"
      + " and ((e.ipAddress = :ipAddress) or (e.ipAddress is null and :ipAddress is null))"
      + " and ((e.userAgent = :userAgent) or (e.userAgent is null and :userAgent is null))")
  long countByUserIdAndOutcomeAndDevicePair(@Param("userId") Long userId,
                                            @Param("outcome") LoginOutcome outcome,
                                            @Param("ipAddress") String ipAddress,
                                            @Param("userAgent") String userAgent);

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
