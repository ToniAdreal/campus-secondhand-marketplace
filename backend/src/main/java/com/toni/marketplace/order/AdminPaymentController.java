package com.toni.marketplace.order;

import com.toni.marketplace.common.ApiResponse;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
  private final PaymentWebhookService webhooks;
  private final PaymentAttemptRecorder attempts;

  AdminPaymentController(OrderService orderService, PaymentWebhookService webhooks,
                         PaymentAttemptRecorder attempts) {
    this.orderService = orderService;
    this.webhooks = webhooks;
    this.attempts = attempts;
  }

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

  /**
   * Webhook-event viewer (backlog #156): the read side of the
   * {@code webhook_event} ledger (#140). Until now the rows were
   * written by the receiver and consumed only by that same service, so
   * reconciling "the PSP says it sent it" against "we applied it"
   * meant raw SQL. Rows are paginated newest first, optionally
   * filtered by exact {@code outcome} (bound to the enum, so anything
   * else is a 400, never a query fragment). Rows are
   * {@link WebhookEventDto} projections — see that record for what is
   * and is not exposed (no order id exists on the ledger; no raw body
   * or signature is stored, so none can leak).
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "unknown outcome filter (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "not an ADMIN (envelope)")})
  @GetMapping("/webhooks")
  public ApiResponse<Page<WebhookEventDto>> webhooks(
      @RequestParam(name = "outcome", required = false) WebhookEvent.Outcome outcome,
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    return ApiResponse.ok(webhooks.listWebhookEvents(outcome, pageable));
  }

  /**
   * Payment-attempt viewer (backlog #211): the read side of the
   * {@code payment_attempt} ledger. Until now a declined capture existed
   * only as a JVM-local Micrometer counter, so an operator could not
   * answer "which orders keep failing to pay?" from data. Rows are
   * paginated newest first, optionally filtered by exact
   * {@code outcome} (bound to the enum, so anything else is a 400,
   * never a query fragment). Rows are {@link PaymentAttemptDto}
   * projections — see that record for what is and is not exposed (the
   * payment token is never stored in the ledger, so none can leak).
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "unknown outcome filter (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "not an ADMIN (envelope)")})
  @GetMapping("/attempts")
  public ApiResponse<Page<PaymentAttemptDto>> paymentAttempts(
      @RequestParam(name = "outcome", required = false) PaymentAttempt.Outcome outcome,
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    return ApiResponse.ok(attempts.listAttempts(outcome, pageable));
  }

  /**
   * Webhook-event replay (backlog #170): re-runs one recorded event
   * through the live delivery's own seams (freshness re-check against
   * the recorded timestamp, then the #161 dedupe / reconcile path) —
   * an already-applied event is a recorded no-op returning its stored
   * outcome, a mismatched event applies exactly once, and a stale
   * event still answers the 401 envelope. Every attempt is counted
   * on the ledger row and audit-logged. The acting ADMIN is the JWT
   * principal — there is no actor parameter anywhere.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "unauthenticated (envelope), or the recorded event is outside the freshness window (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "not an ADMIN (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "unknown webhook event id (envelope)")})
  @PostMapping("/webhooks/{id}/replay")
  public ApiResponse<PaymentWebhookService.WebhookReplayAck> replayWebhook(
      @PathVariable long id) {
    long adminId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(webhooks.replayWebhookEvent(id, adminId));
  }
}
