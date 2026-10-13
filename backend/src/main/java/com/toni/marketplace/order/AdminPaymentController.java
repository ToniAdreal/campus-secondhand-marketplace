package com.toni.marketplace.order;

import com.toni.marketplace.common.ApiResponse;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN payment settlement (backlog #134). Until now the only payment
 * totals in the system were {@code PaymentService}'s JVM in-memory
 * counters — lost on restart, and counting invocations rather than
 * money — so there was no way to answer "how much did the mock PSP
 * capture and refund" from the database of record.
 *
 * <p>All endpoints are ADMIN-only (class-level {@code @PreAuthorize},
 * following the {@code AdminUserController} / {@code AuditLogController}
 * pattern); anonymous callers get the JSON 401 envelope and non-admin
 * callers the JSON 403 envelope.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "admin", description = "ADMIN payment settlement")
@RequestMapping("/api/admin/payments")
@PreAuthorize("hasRole('ADMIN')")
class AdminPaymentController {

  private final OrderService orderService;

  AdminPaymentController(OrderService orderService) {
    this.orderService = orderService;
  }

  /**
   * Settlement summary reconciled from the orders table: captured total
   * (orders carrying a capture id — a refunded order was still captured),
   * refunded total (REFUNDED orders), net, and counts per status. See
   * {@link SettlementSummaryDto} for the exact money semantics and the
   * honest mock-money scope.
   */
  /**
   * Settlement summary, optionally scoped to a date window (backlog
   * #155). {@code from}/{@code to} are ISO-8601 instants bounding
   * {@code Order.createdAt} as {@code [from, to)}, mirroring the seller
   * summary window; either may be omitted (open side), and omitting both
   * returns the all-time summary unchanged. An inverted window is a 400
   * in the JSON envelope.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "inverted date window — from must be before to (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "not an ADMIN (envelope)")})
  @GetMapping("/settlement")
  public ApiResponse<SettlementSummaryDto> settlement(
      @RequestParam(name = "from", required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam(name = "to", required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
    return ApiResponse.ok(orderService.settlementSummary(from, to));
  }
}
