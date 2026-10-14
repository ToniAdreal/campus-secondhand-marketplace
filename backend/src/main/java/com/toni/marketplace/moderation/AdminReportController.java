package com.toni.marketplace.moderation;

import com.toni.marketplace.common.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN moderation queue (backlog #160). All endpoints are ADMIN-only
 * (class-level {@code @PreAuthorize}, mirroring {@code AdminUserController}
 * and {@code AuditLogController}); anonymous callers get the JSON 401
 * envelope and non-admin callers the JSON 403 envelope. Rows are
 * {@link ListingReportDto} projections — never the entity.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "admin", description = "ADMIN moderation queue")
@RequestMapping("/api/admin/reports")
@PreAuthorize("hasRole('ADMIN')")
class AdminReportController {

  private final ListingReportService reportService;

  AdminReportController(ListingReportService reportService) {
    this.reportService = reportService;
  }

  /**
   * Lists reports, newest first, optionally filtered by exact
   * {@code status} (bound to the {@link ReportStatus} enum, so only
   * allowlisted values are accepted — anything else is a 400, never a
   * query fragment). Default page size 20, sorted by {@code createdAt}
   * desc with {@code id} desc as the tie-break; the #75 global cap
   * clamps oversized {@code size} values.
   */
  @GetMapping
  public ApiResponse<Page<ListingReportDto>> list(
      @RequestParam(name = "status", required = false) ReportStatus status,
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    return ApiResponse.ok(reportService.listReports(status, pageable));
  }

  /**
   * Dismisses a report (no action on the listing). Idempotent on an
   * already-DISMISSED report; 422 on an ACTIONED one; 404 on an
   * unknown id.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "report dismissed — or already dismissed (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not an ADMIN (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown report id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "report already actioned (envelope)")})
  @PostMapping("/{id}/dismiss")
  public ApiResponse<ListingReportDto> dismiss(@PathVariable Long id) {
    return ApiResponse.ok(reportService.dismiss(id));
  }

  /**
   * Actions a report: withdraws the listing (AVAILABLE → WITHDRAWN) in
   * the same transaction. Idempotent on an already-ACTIONED report;
   * 422 on a DISMISSED one; 404 on an unknown id.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "report actioned and listing withdrawn — or already actioned (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not an ADMIN (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown report id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "report already dismissed (envelope)")})
  @PostMapping("/{id}/action")
  public ApiResponse<ListingReportDto> action(@PathVariable Long id) {
    return ApiResponse.ok(reportService.action(id));
  }
}
