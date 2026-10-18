package com.toni.marketplace.moderation;

import com.toni.marketplace.common.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN report-appeal queue (backlog #190). All endpoints are
 * ADMIN-only (class-level {@code @PreAuthorize}, mirroring
 * {@code AdminDisputeController}); anonymous callers get the JSON 401
 * envelope and non-admin callers the JSON 403 envelope. Rows are
 * {@link ReportAppealDto} projections — never the entity. The
 * resolving ADMIN's id is taken from the JWT principal for the audit
 * trail.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "admin", description = "ADMIN report-appeal queue")
@RequestMapping("/api/admin/appeals")
@PreAuthorize("hasRole('ADMIN')")
class AdminAppealController {

  private final ReportAppealService appealService;

  AdminAppealController(ReportAppealService appealService) {
    this.appealService = appealService;
  }

  /**
   * Lists appeals, newest first, optionally filtered by exact
   * {@code status} (bound to the {@link ReportAppealStatus} enum, so
   * only allowlisted values are accepted — anything else is a 400).
   * Default page size 20, sorted by {@code createdAt} desc with
   * {@code id} desc as the tie-break; the #75 global cap clamps
   * oversized sizes.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not an ADMIN (envelope)")})
  @GetMapping
  public ApiResponse<Page<ReportAppealDto>> list(
      @RequestParam(name = "status", required = false) ReportAppealStatus status,
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    return ApiResponse.ok(appealService.listAppeals(status, pageable));
  }

  /**
   * Upholds an appeal: the listing returns to AVAILABLE when it is
   * still WITHDRAWN; a listing that moved on is left untouched and the
   * row records {@code listingRestored=false}. Idempotent on an
   * already-upheld appeal; 422 on a rejected one; 404 on an unknown
   * id.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "appeal upheld — or already upheld (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not an ADMIN (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown appeal id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
          description = "version conflict with a concurrent resolution (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "appeal already rejected (envelope)")})
  @PostMapping("/{id}/uphold")
  public ApiResponse<ReportAppealDto> uphold(@PathVariable Long id) {
    return ApiResponse.ok(appealService.uphold(currentUserId(), id));
  }

  /**
   * Rejects an appeal: the report's withdrawal stands. Idempotent on
   * an already-rejected appeal; 422 on an upheld one; 404 on an
   * unknown id.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "appeal rejected — or already rejected (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not an ADMIN (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown appeal id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
          description = "version conflict with a concurrent resolution (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "appeal already upheld (envelope)")})
  @PostMapping("/{id}/reject")
  public ApiResponse<ReportAppealDto> reject(@PathVariable Long id) {
    return ApiResponse.ok(appealService.reject(currentUserId(), id));
  }

  private static Long currentUserId() {
    return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }
}
