package com.toni.marketplace.dispute;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN dispute queue (backlog #169). All endpoints are ADMIN-only
 * (class-level {@code @PreAuthorize}, mirroring
 * {@code AdminReportController}); anonymous callers get the JSON 401
 * envelope and non-admin callers the JSON 403 envelope. Rows are
 * {@link DisputeDto} projections — never the entity. The resolving
 * ADMIN's id is taken from the JWT principal for the audit trail.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "admin", description = "ADMIN dispute queue")
@RequestMapping("/api/admin/disputes")
@PreAuthorize("hasRole('ADMIN')")
class AdminDisputeController {

  private final DisputeService disputeService;

  AdminDisputeController(DisputeService disputeService) {
    this.disputeService = disputeService;
  }

  /**
   * Lists disputes, newest first, optionally filtered by exact
   * {@code status} (bound to the {@link DisputeStatus} enum, so only
   * allowlisted values are accepted — anything else is a 400). Default
   * page size 20, sorted by {@code createdAt} desc with {@code id} desc
   * as the tie-break; the #75 global cap clamps oversized sizes.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "dispute queue page (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not an ADMIN (envelope)")})
  @GetMapping
  public ApiResponse<Page<DisputeDto>> list(
      @RequestParam(name = "status", required = false) DisputeStatus status,
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    return ApiResponse.ok(disputeService.listDisputes(status, pageable));
  }

  /**
   * Resolves a dispute by refunding the order through the mock PSP
   * seam (exactly once) and returning the listing to AVAILABLE unless
   * it was already SOLD. Requires a resolution note (backlog #240:
   * blank/absent/over-1000 chars → 400); the note is stored on the
   * dispute row and shown to both parties. Idempotent on an
   * already-refunded dispute (the first note stands); 422 on a
   * released one; 404 on an unknown id.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "dispute resolved by refund — or already resolved that way (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
          description = "resolution note missing/blank or over 1000 characters (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not an ADMIN (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown dispute id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
          description = "version conflict with a concurrent resolution (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "dispute already resolved by release (envelope)")})
  @PostMapping("/{id}/resolve-refund")
  public ApiResponse<DisputeDto> resolveRefund(@PathVariable Long id,
                                               @Valid @RequestBody DisputeResolveRequest request) {
    return ApiResponse.ok(
        disputeService.resolveRefund(currentUserId(), id, request.resolutionNote()));
  }

  /**
   * Resolves a dispute by releasing the order back to its recorded
   * pre-dispute status. Requires a resolution note (backlog #240:
   * blank/absent/over-1000 chars → 400); the note is stored on the
   * dispute row and shown to both parties. Idempotent on an
   * already-released dispute (the first note stands); 422 on a
   * refunded one; 404 on an unknown id.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "dispute resolved by release — or already resolved that way (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
          description = "resolution note missing/blank or over 1000 characters (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not an ADMIN (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown dispute id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
          description = "version conflict with a concurrent resolution (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "dispute already resolved by refund (envelope)")})
  @PostMapping("/{id}/resolve-release")
  public ApiResponse<DisputeDto> resolveRelease(@PathVariable Long id,
                                                @Valid @RequestBody DisputeResolveRequest request) {
    return ApiResponse.ok(
        disputeService.resolveRelease(currentUserId(), id, request.resolutionNote()));
  }

  private static Long currentUserId() {
    return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }
}
