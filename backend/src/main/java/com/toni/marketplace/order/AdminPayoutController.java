package com.toni.marketplace.order;

import com.toni.marketplace.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN payout ledger (backlog #390). The one ADMIN-only payout write:
 * marking a PAYABLE settlement row PAID by hand, outside the hourly
 * sweep — for an operator settling a payout directly. All endpoints are
 * ADMIN-only (class-level {@code @PreAuthorize}, mirroring
 * {@code AdminDisputeController}); anonymous callers get the JSON 401
 * envelope and non-admin callers the JSON 403 envelope. The acting
 * ADMIN's id is taken from the JWT principal for the audit trail:
 * a successful mark-paid writes one {@code PAYOUT_MARKED_PAID} row
 * (actor = the ADMIN, target = the payout row) inside the transition's
 * own transaction. The hourly sweep's own PAYABLE → PAID flip writes
 * no audit row — it is a system transition with no actor (the #122
 * rule) — so this endpoint is the audited mark-paid.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "admin", description = "ADMIN payout ledger")
@RequestMapping("/api/admin/payouts")
@PreAuthorize("hasRole('ADMIN')")
class AdminPayoutController {

  private final SellerPayoutService payoutService;

  AdminPayoutController(SellerPayoutService payoutService) {
    this.payoutService = payoutService;
  }

  /**
   * Marks a PAYABLE payout PAID by hand. Same guards as the sweep
   * (PAYABLE status, order still COMPLETED, no open dispute, seller
   * not held) and the same PAYOUT_PAID seller notification. Idempotent
   * on an already-PAID row (the row is returned, no second audit row);
   * 422 on any other unpayable state; 404 on an unknown id.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "payout marked PAID — or already PAID (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not an ADMIN (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown payout id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "payout cannot be marked paid in its current state (envelope)")})
  @PostMapping("/{id}/mark-paid")
  public ApiResponse<SellerPayoutDto> markPaid(@PathVariable Long id) {
    return ApiResponse.ok(payoutService.markPaidByAdmin(currentUserId(), id));
  }

  private static Long currentUserId() {
    return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }
}
