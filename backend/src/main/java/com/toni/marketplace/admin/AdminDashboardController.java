package com.toni.marketplace.admin;

import com.toni.marketplace.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN operations dashboard (backlog #196): GET /api/admin/dashboard.
 * Until now an ADMIN answered "is the marketplace healthy today" by
 * opening the audit log (#109), the settlement summary (#134) and the
 * webhook viewer (#156) side by side and running the rest as manual
 * queries; this is the single aggregate surface. ADMIN-only
 * (class-level {@code @PreAuthorize}, following the
 * {@code AdminPaymentController} pattern); anonymous callers get the
 * JSON 401 envelope and non-admin callers the JSON 403 envelope.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(
    name = "admin", description = "ADMIN operations dashboard")
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminDashboardController {

  private final AdminDashboardService dashboard;

  AdminDashboardController(AdminDashboardService dashboard) {
    this.dashboard = dashboard;
  }

  /**
   * Aggregate counts for one bounded look-back window. {@code window}
   * is one of {@code 24h} / {@code 7d} / {@code 30d} (omitted →
   * {@code 24h}); anything else is a 400 in the JSON envelope. The
   * payload is counts only — no row data, no user identifiers (see
   * {@link DashboardDto}).
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "400", description = "unknown window — must be 24h, 7d or 30d (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "401", description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "403", description = "not an ADMIN (envelope)")})
  @GetMapping("/dashboard")
  public ApiResponse<DashboardDto> dashboard(
      @RequestParam(name = "window", required = false) String window) {
    return ApiResponse.ok(dashboard.dashboard(DashboardWindow.parse(window)));
  }
}
