package com.toni.marketplace.audit;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Append-only audit trail for sensitive transitions (backlog #98). Before
 * this, a password change, a TOTP enable, an order complete/refund or an
 * ADMIN disable/enable left no trace beyond application logs.
 *
 * <p>Covered in v1 (the seams that exist today): {@code PASSWORD_CHANGED}
 * and {@code TOTP_ENABLED} from {@code AuthService}, {@code USER_DISABLED}
 * / {@code USER_ENABLED} from the ADMIN endpoints (#88), and
 * {@code ORDER_COMPLETED} / {@code ORDER_REFUNDED} from
 * {@code OrderService}. Declared follow-ups, deliberately not in v1:
 * password-reset confirm (its actor is a token holder, not an
 * authenticated principal — attribution needs its own decision) and a
 * read endpoint (the trail is write-only for now; operators read it via
 * SQL).
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

  /** Appends one row: {@code actorUserId} performed {@code action} on the target. */
  public void record(long actorUserId, AuditAction action, AuditTargetType targetType,
                     long targetId) {
    if (auditLog == null) {
      return; // noop() instance — see above
    }
    auditLog.save(new AuditLog(actorUserId, action, targetType, targetId, clock.instant()));
  }
}
