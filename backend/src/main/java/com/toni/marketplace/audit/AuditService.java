package com.toni.marketplace.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
 * {@code ORDER_PAID} / {@code ORDER_CANCELLED} (backlog #122) and
 * {@code ORDER_COMPLETED} / {@code ORDER_REFUNDED} from
 * {@code OrderService}, {@code CATEGORY_CREATED} /
 * {@code CATEGORY_UPDATED} / {@code CATEGORY_DELETED} from
 * {@code CategoryService} (backlog #132 — the ADMIN-only taxonomy writes
 * previously left no trail at all), and {@code PASSWORD_RESET_COMPLETED} from
 * {@code PasswordResetService} (backlog #110 — attribution decision:
 * the reset token resolves to exactly one account, so actor and target
 * are both that account; the row evidences a credential change via the
 * reset path, distinct from the authenticated {@code PASSWORD_CHANGED}),
 * and {@code DATA_EXPORTED} from {@code AccountDataExportService}
 * (backlog #172 — the one audited READ: the export hands the caller
 * every section of their own data, so the read itself is recorded,
 * actor = target = the account holder).
 * The read side landed in backlog #109:
 * {@link #listAuditLog} behind {@code GET /api/admin/audit-log}, and the
 * CSV export in backlog #232 ({@link #exportAuditLog} behind
 * {@code GET /api/admin/audit-log/export}) reuses the same filters and
 * projections under the same ADMIN-only guard.
 *
 * <p>Deliberately NOT covered (backlog #122): stale-order expiry
 * ({@code OrderService.expireStaleOrder}). Expiry is a system transition
 * with no actor — stamping the buyer as the actor of an
 * {@code ORDER_CANCELLED} row for an expiry would falsify the trail, so
 * expiries write no row at all.
 *
 * <p>Transaction contract: {@link #record} carries no {@code @Transactional}
 * of its own, so the repository save joins the caller's transaction — an
 * audited action that rolls back leaves no audit row, and callers invoke it
 * only on the real transition path (idempotent no-op repeats return before
 * reaching it and write none).
 */
@Service
public class AuditService {

  /**
   * Maximum rows one audit-log export returns (backlog #232). This mirrors
   * the #75 discipline — {@code spring.data.web.pageable.max-page-size}
   * in {@code application.yml} is 100 — applied to the export as a row
   * cap instead of a page size: the export is one bounded page, never an
   * unbounded scan of a table the retention job keeps for 365 days.
   * Operators who need more narrow the trail with the exact-match
   * filters first; when the filtered trail is longer than the cap the
   * export reports truncation via {@link ExportResult#truncated()} rather
   * than silently pretending the file is complete. If max-page-size ever
   * changes, update this constant with it.
   */
  static final int EXPORT_ROW_CAP = 100;

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

  /**
   * One bounded audit-log export (backlog #232): the same optional
   * exact-match filters as {@link #listAuditLog}, the same newest-first
   * order (createdAt DESC, id DESC as tie-break), but a single page of
   * at most {@link #EXPORT_ROW_CAP} rows instead of caller-chosen
   * pagination — an export must never be an unbounded scan. Rows are
   * materialized to {@link AuditLogDto} inside this transaction, so the
   * controller's stream only formats detached records. {@code truncated}
   * is true exactly when the filtered trail holds more rows than the
   * cap, so the caller can say so (via the response header) instead of
   * passing off a partial file as complete. The {@code noop()} instance
   * answers an empty, untruncated export, mirroring {@link #listAuditLog}.
   */
  record ExportResult(List<AuditLogDto> rows, boolean truncated, Instant exportedAt) {}

  @Transactional(readOnly = true)
  public ExportResult exportAuditLog(Long actorUserId, AuditAction action,
                                     AuditTargetType targetType, Long targetId) {
    if (auditLog == null) {
      return new ExportResult(List.of(), false, clock.instant());
    }
    Page<AuditLogDto> page = auditLog.searchAuditLog(actorUserId, action, targetType, targetId,
            PageRequest.of(0, EXPORT_ROW_CAP, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
        .map(AuditLogDto::of);
    return new ExportResult(page.getContent(), page.getTotalElements() > page.getContent().size(),
        clock.instant());
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
