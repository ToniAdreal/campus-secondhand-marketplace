package com.toni.marketplace.audit;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

  /**
   * ADMIN audit-log read (backlog #109): every filter is an optional
   * exact match — a {@code null} parameter disables that predicate. The
   * JPQL is a fixed string with bound parameters only; filter values
   * arrive as typed ids / allowlisted enum constants, never as query
   * text.
   */
  @Query("select a from AuditLog a where (:actorUserId is null or a.actorUserId = :actorUserId)"
      + " and (:action is null or a.action = :action)"
      + " and (:targetType is null or a.targetType = :targetType)"
      + " and (:targetId is null or a.targetId = :targetId)")
  Page<AuditLog> searchAuditLog(@Param("actorUserId") Long actorUserId,
                                @Param("action") AuditAction action,
                                @Param("targetType") AuditTargetType targetType,
                                @Param("targetId") Long targetId,
                                Pageable pageable);

  /** Every row recorded against one target, oldest first. */
  List<AuditLog> findByTargetTypeAndTargetIdOrderByIdAsc(AuditTargetType targetType, Long targetId);

  /** Every row one actor produced, oldest first. */
  List<AuditLog> findByActorUserIdOrderByIdAsc(Long actorUserId);

  /** How many rows exist for one action (trail-size assertions in tests). */
  long countByAction(AuditAction action);

  /**
   * Ids of rows created before {@code createdBefore} — the retention
   * cut-off computed by the nightly purge job (backlog #118). The
   * predicate is {@code created_at} only: never action, never actor, so
   * the purge cannot selectively erase specific events. Ordered by id so
   * the batch loop can always re-read the first page without skipping
   * rows.
   */
  @Query("select a.id from AuditLog a where a.createdAt < :createdBefore order by a.id")
  List<Long> findPurgeableIds(Instant createdBefore, Pageable page);
}
