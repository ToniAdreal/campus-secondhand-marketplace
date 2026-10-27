package com.toni.marketplace.order;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "orders", description = "Buyer orders and the mock-PSP payment lifecycle")
@RequestMapping("/api/orders")
public class OrderController {

  private final OrderService orderService;
  private final OrderReceiptService receipts;

  public OrderController(OrderService orderService, OrderReceiptService receipts) {
    this.orderService = orderService;
    this.receipts = receipts;
  }

  /**
   * Lists the caller's own orders, newest first. Default page size 20,
   * sorted by {@code createdAt} desc with {@code id} desc as the tie-break
   * (two orders can share a createdAt instant); the client may override
   * with {@code ?page=&size=&sort=} like on the items list view.
   *
   * <p>Optional {@code ?status=} filter (backlog #123): bound to the
   * {@link OrderStatus} enum, so only allowlisted values reach the query
   * and anything else is answered 400 by the type-mismatch handler.
   * Omitting it keeps the unfiltered list.
   */
  @GetMapping
  public ApiResponse<Page<OrderDto>> list(
      @RequestParam(name = "status", required = false) OrderStatus status,
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    Long buyerId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(orderService.listOrders(buyerId, status, pageable));
  }

  /**
   * Reads one order. The buyer who placed it — or an ADMIN — may read;
   * anyone else gets 403; anonymous callers get the JSON 401 envelope from
   * the security entry point; unknown ids get 404.
   */
  @GetMapping("/{id}")
  public ApiResponse<OrderDto> get(@PathVariable Long id) {
    Authentication authentication =
        SecurityContextHolder.getContext().getAuthentication();
    Long callerId = (Long) authentication.getPrincipal();
    boolean admin = authentication.getAuthorities().stream()
        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    return ApiResponse.ok(orderService.getOrderFor(callerId, admin, id));
  }

  /**
   * Reads the printable receipt for one order (backlog #180). The buyer,
   * the listing's seller, or an ADMIN may read; anyone else gets 404 —
   * the same answer as an unknown id, so a receipt URL never leaks that
   * an order exists. The DTO is a dedicated field-by-field projection
   * (see {@code ReceiptDto}); a PENDING order's receipt is marked unpaid.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "unauthenticated (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "unknown order id, or the caller is not a party to the order (envelope)")})
  @GetMapping("/{id}/receipt")
  public ApiResponse<ReceiptDto> receipt(@PathVariable Long id) {
    Authentication authentication =
        SecurityContextHolder.getContext().getAuthentication();
    Long callerId = (Long) authentication.getPrincipal();
    boolean admin = authentication.getAuthorities().stream()
        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    return ApiResponse.ok(receipts.getReceiptFor(callerId, admin, id));
  }

  /**
   * Creates an order for the authenticated buyer. {@code Idempotency-Key} is
   * required: a retried request with the same key and the same item returns
   * the original order instead of creating a duplicate; the same key with a
   * different item is rejected 422. A missing header is answered 400 by
   * {@code GlobalExceptionHandler#handleMissingHeader}.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "missing Idempotency-Key header or validation failure (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "unauthenticated (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "the item already has an active order, or a version conflict (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "the Idempotency-Key was already used with a different item (envelope)")})
  @PostMapping
  public ApiResponse<OrderDto> create(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody OrderCreateRequest request) {
    Long buyerId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(orderService.createOrder(buyerId, request.itemId(), idempotencyKey));
  }

  /**
   * Cancels an order (PENDING → CANCELLED) and releases the listing back to
   * AVAILABLE so the list view offers it again. Only the order's buyer may
   * cancel (403 otherwise); already-PAID or otherwise terminal orders are
   * rejected 422 (a re-cancel of an already-CANCELLED order is idempotent).
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "unauthenticated (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "not the order's buyer (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "unknown order id (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "version conflict with a concurrent transition (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "order is not PENDING (envelope)")})
  @PostMapping("/{id}/cancel")
  public ApiResponse<OrderDto> cancel(@PathVariable Long id) {
    Long buyerId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(orderService.cancel(buyerId, id));
  }

  /**
   * Captures payment for an order (mock PSP — see {@code PaymentService}).
   * Only the order's buyer may pay (403 otherwise); paying an already-PAID
   * order is idempotent and returns the order unchanged; a {@code @Version}
   * conflict on the order row surfaces as 409. The request body is
   * optional: {@code {"paymentToken":"tok_decline"}} deterministically
   * declines the mock capture → 402, the order stays PENDING, and a retry
   * with any other token (or no body) can still succeed (backlog #92).
   * A successful capture's response also carries the buyer's handover
   * code exactly once (backlog #179) — the seller must present it to
   * complete the order.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "unauthenticated (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "402", description = "mock PSP declined the capture — order stays PENDING, retry may succeed (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "not the order's buyer (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "unknown order id (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "version conflict with a concurrent transition (envelope)")})
  @PostMapping("/{id}/pay")
  public ApiResponse<OrderDto> pay(
      @PathVariable Long id, @RequestBody(required = false) PayRequest request) {
    Long buyerId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(orderService.pay(
        buyerId, id, request == null ? null : request.paymentToken()));
  }

  /**
   * Marks an order COMPLETED — the seller confirms the handoff after the
   * buyer paid. Only the listing's seller (or an ADMIN) may complete (403
   * otherwise, buyer included); only PAID orders transition (anything else
   * → 422, re-completing a COMPLETED order is idempotent); the listing
   * flips RESERVED → SOLD in the same transaction, and a {@code @Version}
   * conflict surfaces as 409.
   *
   * <p>Backlog #179: a seller caller must present the buyer's handover
   * code in the body ({@code {"handoverCode":"123456"}}) — missing and
   * wrong codes are the identical 400, and repeated failures lock the
   * code. An ADMIN caller may omit it: that completion is an explicit
   * override, audited as such. The body is optional for exactly that
   * ADMIN case.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "missing or wrong handover code for a seller completion (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "unauthenticated (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "not the listing's seller or an ADMIN (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "unknown order id (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "version conflict with a concurrent transition (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "order is not PAID (envelope)")})
  @PostMapping("/{id}/complete")
  public ApiResponse<OrderDto> complete(
      @PathVariable Long id, @RequestBody(required = false) CompleteOrderRequest request) {
    Authentication authentication =
        SecurityContextHolder.getContext().getAuthentication();
    Long callerId = (Long) authentication.getPrincipal();
    boolean admin = authentication.getAuthorities().stream()
        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    return ApiResponse.ok(orderService.complete(callerId, admin, id,
        request == null ? null : request.handoverCode()));
  }

  /**
   * Ships a PAID order (backlog #222) — the mailed-sale path: the seller
   * records the carrier and tracking number, PAID → SHIPPED, and the
   * listing stays RESERVED until the buyer confirms receipt. Only the
   * listing's seller (or an ADMIN) may ship (403 otherwise); anything
   * but PAID → 422 (shipping a handover-completed order, or re-shipping,
   * are both 422); blank or over-long carrier/tracking → 400 envelope
   * via jakarta validation.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "missing or over-long carrier/tracking number (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "unauthenticated (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "not the listing's seller or an ADMIN (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "unknown order id (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "version conflict with a concurrent transition (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "order is not PAID (envelope)")})
  @PostMapping("/{id}/ship")
  public ApiResponse<OrderDto> ship(
      @PathVariable Long id, @Valid @RequestBody ShipOrderRequest request) {
    Authentication authentication =
        SecurityContextHolder.getContext().getAuthentication();
    Long callerId = (Long) authentication.getPrincipal();
    boolean admin = authentication.getAuthorities().stream()
        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    return ApiResponse.ok(orderService.ship(callerId, admin, id,
        request.carrier(), request.trackingNumber()));
  }

  /**
   * Confirms receipt of a SHIPPED order (backlog #222): the buyer closes
   * the mailed-sale path, SHIPPED → COMPLETED with the same RESERVED →
   * SOLD listing flip, settlement row and audit trail as the handover
   * completion. Only the order's buyer may confirm (403 otherwise);
   * anything but SHIPPED → 422 (a PAID order has nothing to confirm
   * yet); re-confirming a COMPLETED order is idempotent.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "unauthenticated (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "not the order's buyer (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "unknown order id (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "version conflict with a concurrent transition (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "order is not SHIPPED (envelope)")})
  @PostMapping("/{id}/confirm-receipt")
  public ApiResponse<OrderDto> confirmReceipt(@PathVariable Long id) {
    Long buyerId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(orderService.confirmReceipt(buyerId, id));
  }

  /**
   * Regenerates the buyer's handover code (backlog #179): the old code
   * is invalidated and the new plaintext is returned in this response —
   * its one disclosure. Buyer-only (403 otherwise); only while the order
   * is PAID (422 otherwise). The recovery path for a buyer who lost the
   * code shown at pay time.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "unauthenticated (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "not the order's buyer (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "unknown order id (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "order is not PAID (envelope)")})
  @PostMapping("/{id}/handover-code/regenerate")
  public ApiResponse<OrderDto> regenerateHandoverCode(@PathVariable Long id) {
    Long buyerId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(orderService.regenerateHandoverCode(buyerId, id));
  }

  /**
   * Refunds a captured order (mock PSP — see {@code PaymentService}):
   * PAID → REFUNDED. Only the listing's seller (or an ADMIN) may refund —
   * the buyer cannot self-refund after capture (deliberate design decision,
   * see the service javadoc); anything but PAID → 422, and a re-refund of
   * an already-REFUNDED order is idempotent. The listing flips
   * RESERVED → AVAILABLE in the same transaction, and a {@code @Version}
   * conflict surfaces as 409. Partial refunds (backlog #210): the
   * optional body may carry {@code amountCents} — the order then stays
   * PAID with a cumulative {@code refundedAmountCents} until the
   * refunded total reaches the order amount; an absent amount refunds
   * everything still refundable. A reason is required on every call
   * (backlog #239): blank or absent → 400, over 500 chars → 400; the
   * reason is stored on a REFUNDED payment-attempt row and surfaced in
   * the ADMIN attempt viewer and on the order receipt.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "missing, blank or over-500-char refund reason (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "unauthenticated (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "not the listing's seller or an ADMIN (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "unknown order id (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "version conflict with a concurrent transition (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "order is not PAID (envelope)")})
  @PostMapping("/{id}/refund")
  public ApiResponse<OrderDto> refund(
      @PathVariable Long id, @RequestBody(required = false) RefundOrderRequest request) {
    Authentication authentication =
        SecurityContextHolder.getContext().getAuthentication();
    Long callerId = (Long) authentication.getPrincipal();
    boolean admin = authentication.getAuthorities().stream()
        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    return ApiResponse.ok(orderService.refund(callerId, admin, id,
        request == null ? null : request.amountCents(),
        request == null ? null : request.reason()));
  }
}
