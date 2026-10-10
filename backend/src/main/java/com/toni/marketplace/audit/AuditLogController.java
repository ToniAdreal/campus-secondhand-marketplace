package com.toni.marketplace.audit;

import com.toni.marketplace.common.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN read side of the audit trail (backlog #109). Until now the trail
 * (#98) was write-only — operators read it via SQL. All endpoints are
 * ADMIN-only (class-level {@code @PreAuthorize}, mirroring
 * {@code AdminUserController}); anonymous callers get the JSON 401
 * envelope and non-admin callers the JSON 403 envelope. There is still
 * no write path here: rows are appended only by {@link AuditService}
 * from inside audited transitions, and no admin UI exists yet.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "admin", description = "ADMIN audit trail")
@RequestMapping("/api/admin/audit-log")
@PreAuthorize("hasRole('ADMIN')")
class AuditLogController {

  private final AuditService audit;

  AuditLogController(AuditService audit) {
    this.audit = audit;
  }

  /**
   * Lists audit rows, newest first, optionally filtered by exact match:
   * {@code actorUserId}, {@code action}, and {@code targetType} /
   * {@code targetId} (each independently optional; together they narrow
   * to one target's trail). {@code action} and {@code targetType} bind
   * to their enums, so only allowlisted values are accepted — anything
   * else is a 400, never a query fragment. Default page size 20, sorted
   * by {@code createdAt} desc with {@code id} desc as the tie-break; the
   * #75 global cap clamps oversized {@code size} values. Rows are
   * {@link AuditLogDto} projections — never the entity.
   */
  @GetMapping
  public ApiResponse<Page<AuditLogDto>> list(
      @RequestParam(name = "actorUserId", required = false) Long actorUserId,
      @RequestParam(name = "action", required = false) AuditAction action,
      @RequestParam(name = "targetType", required = false) AuditTargetType targetType,
      @RequestParam(name = "targetId", required = false) Long targetId,
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    return ApiResponse.ok(audit.listAuditLog(actorUserId, action, targetType, targetId, pageable));
  }
}
