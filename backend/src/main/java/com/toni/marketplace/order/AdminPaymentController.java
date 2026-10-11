package com.toni.marketplace.order;

import com.toni.marketplace.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
  @GetMapping("/settlement")
  public ApiResponse<SettlementSummaryDto> settlement() {
    return ApiResponse.ok(orderService.settlementSummary());
  }
}
