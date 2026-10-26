package com.toni.marketplace.audit;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.constraints.Pattern;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * ADMIN read side of the audit trail (backlog #109). Until now the trail
 * (#98) was write-only — operators read it via SQL. All endpoints are
 * ADMIN-only (class-level {@code @PreAuthorize}, mirroring
 * {@code AdminUserController}); anonymous callers get the JSON 401
 * envelope and non-admin callers the JSON 403 envelope. There is still
 * no write path here: rows are appended only by {@link AuditService}
 * from inside audited transitions, and no admin UI exists yet. The CSV
 * export (backlog #232) shares this class-level guard and the list
 * endpoint's filters.
 */
@RestController
@Validated
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

  /**
   * CSV export of the audit trail (backlog #232):
   * {@code GET /api/admin/audit-log/export?format=csv}. Same ADMIN-only
   * guard, same optional exact-match filters, same newest-first order
   * as {@link #list}, but as a {@code text/csv} attachment whose
   * filename carries the UTC export date
   * ({@code audit-log-&lt;yyyy-MM-dd&gt;.csv}, mirroring the account
   * data export's dated filename). Rows stream one at a time through a
   * {@link StreamingResponseBody} — the rows themselves are materialized
   * to {@link AuditLogDto} inside the service transaction, so the stream
   * only formats detached records. The export is capped at
   * {@link AuditService#EXPORT_ROW_CAP} rows (the #75 discipline); when
   * the filtered trail is longer, the response carries
   * {@code X-Audit-Export-Truncated: true} instead of silently passing
   * off a partial file as complete — narrow with the filters to get the
   * rest. {@code format} defaults to {@code csv} and is {@code @Pattern}-
   * validated (the {@code @Validated} controller + the global handler's
   * {@code ConstraintViolationException} mapping, mirroring
   * {@code ItemController}'s query-param validation): anything else is
   * a 400 in the JSON envelope (the error answer is JSON even though
   * the success answer is CSV, mirroring the seller sales export), as
   * is an out-of-allowlist enum filter value via the global handler.
   * The declared return type is {@code ResponseEntity<StreamingResponseBody>}
   * deliberately — a wildcard body type defeats Spring's streaming
   * return-value handler and the response completes synchronously.
   */
  @GetMapping(value = "/export", produces = "text/csv")
  public ResponseEntity<StreamingResponseBody> exportCsv(
      @RequestParam(name = "actorUserId", required = false) Long actorUserId,
      @RequestParam(name = "action", required = false) AuditAction action,
      @RequestParam(name = "targetType", required = false) AuditTargetType targetType,
      @RequestParam(name = "targetId", required = false) Long targetId,
      @RequestParam(name = "format", required = false, defaultValue = "csv")
      @Pattern(regexp = "(?i)csv", message = "format must be csv") String format) {
    AuditService.ExportResult result = audit.exportAuditLog(actorUserId, action, targetType, targetId);
    String filename = "audit-log-"
        + DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
            .format(result.exportedAt()) + ".csv";
    StreamingResponseBody body = out -> {
      Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
      writer.write(AuditLogCsv.HEADER);
      writer.write("\r\n");
      for (AuditLogDto row : result.rows()) {
        writer.write(AuditLogCsv.row(row));
        writer.write("\r\n");
      }
      writer.flush();
    };
    ResponseEntity.BodyBuilder response = ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
    if (result.truncated()) {
      response.header("X-Audit-Export-Truncated", "true");
    }
    return response.body(body);
  }
}
