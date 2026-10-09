package com.toni.marketplace.audit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

  /** Every row recorded against one target, oldest first. */
  List<AuditLog> findByTargetTypeAndTargetIdOrderByIdAsc(AuditTargetType targetType, Long targetId);

  /** Every row one actor produced, oldest first. */
  List<AuditLog> findByActorUserIdOrderByIdAsc(Long actorUserId);

  /** How many rows exist for one action (trail-size assertions in tests). */
  long countByAction(AuditAction action);
}
