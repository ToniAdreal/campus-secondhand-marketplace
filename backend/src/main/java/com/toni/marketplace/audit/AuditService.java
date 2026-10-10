package com.toni.marketplace.audit;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only audit trail for sensitive transitions (backlog #98). Before
 * this, a password change, a TOTP enable, an order complete/refund or an
 * ADMIN disable/enable left no trace beyond application logs.
 *
 * <p>Covered in v1 (the seams that exist today): {@code PASSWORD_CHANGED},
 * {@code TOTP_ENABLED} and {@code TOTP_DISABLED} (backlog #121) from
 * {@code AuthService}, {@code USER_DISABLED}
 * / {@code USER_ENABLED} from the ADMIN endpoints (#88), and
 * {@code ORDER_COMPLETED} / {@code ORDER_REFUNDED} from
 * {@code OrderService}, and {@code PASSWORD_RESET_COMPLETED} from
 * {@code PasswordResetService} (backlog #110 — attribution decision:
 * the reset token resolves to exactly one account, so actor and target
 * are both that account; the row evidences a credential change via the
 * reset path, distinct from the authenticated {@code PASSWORD_CHANGED}).
 * The read side landed in backlog #109:
 * {@link #listAuditLog} behind {@code GET /api/admin/audit-log}.
 *
 * <p>Transaction contract: {@link #record} carries no {@code @Transactional}
 * of its own, so the repository save joins the caller's transaction — an
 * audited action that rolls back leaves no audit row, and callers invoke it
 * only on the real transition path (idempotent no-op repeats return before
 * reaching it and write none).
 */
@Service
public class AuditService {

  private final AuditLogRepository auditLog;
  private final Clock clock;

  @Autowired
  public AuditService(AuditLogRepository auditLog, Clock clock) {
    this.auditLog = auditLog;
    this.clock = clock;
  }

  private AuditService() {
    this.auditLog = null;
    this.clock = Clock.systemUTC();
  }

  /**
   * No-op instance for the legacy direct-unit-test constructors of the
   * calling services, mirroring {@code AuthMetrics.noop()}: tests that
   * construct a service by hand assert on their own subject, not on the
   * trail. Production always gets the Spring bean.
   */
  public static AuditService noop() {
    return new AuditService();
  }

  /**
   * ADMIN audit-log read (backlog #109): paginated, newest first (the
   * controller's {@code @PageableDefault} supplies createdAt/id DESC;
   * the global max-page-size cap from #75 clamps oversized {@code size}).
   * Every filter is an optional exact match; enum filters bind to
   * {@link AuditAction} / {@link AuditTargetType}, so only allowlisted
   * values can reach the query. Rows map to {@link AuditLogDto} — never
   * the entity. The {@code noop()} instance has no repository and
   * answers an empty page, mirroring {@link #record}'s no-op contract.
   */
  @Transactional(readOnly = true)
  public Page<AuditLogDto> listAuditLog(Long actorUserId, AuditAction action,
                                        AuditTargetType targetType, Long targetId,
                                        Pageable pageable) {
    if (auditLog == null) {
      return Page.empty(pageable); // noop() instance — see above
    }
    return auditLog.searchAuditLog(actorUserId, action, targetType, targetId, pageable)
        .map(AuditLogDto::of);
  }

  /** Appends one row: {@code actorUserId} performed {@code action} on the target. */
  public void record(long actorUserId, AuditAction action, AuditTargetType targetType,
                     long targetId) {
    if (auditLog == null) {
      return; // noop() instance — see above
    }
    auditLog.save(new AuditLog(actorUserId, action, targetType, targetId, clock.instant()));
  }
}
