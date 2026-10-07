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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

  private final OrderService orderService;

  public OrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  /**
   * Lists the caller's own orders, newest first. Default page size 20,
   * sorted by {@code createdAt} desc with {@code id} desc as the tie-break
   * (two orders can share a createdAt instant); the client may override
   * with {@code ?page=&size=&sort=} like on the items list view.
   */
  @GetMapping
  public ApiResponse<Page<OrderDto>> list(
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    Long buyerId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(orderService.listOrders(buyerId, pageable));
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
   * Creates an order for the authenticated buyer. {@code Idempotency-Key} is
   * required: a retried request with the same key and the same item returns
   * the original order instead of creating a duplicate; the same key with a
   * different item is rejected 422. A missing header is answered 400 by
   * {@code GlobalExceptionHandler#handleMissingHeader}.
   */
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
   * conflict on the order row surfaces as 409.
   */
  @PostMapping("/{id}/pay")
  public ApiResponse<OrderDto> pay(@PathVariable Long id) {
    Long buyerId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(orderService.pay(buyerId, id));
  }

  /**
   * Marks an order COMPLETED — the seller confirms the handoff after the
   * buyer paid. Only the listing's seller (or an ADMIN) may complete (403
   * otherwise, buyer included); only PAID orders transition (anything else
   * → 422, re-completing a COMPLETED order is idempotent); the listing
   * flips RESERVED → SOLD in the same transaction, and a {@code @Version}
   * conflict surfaces as 409.
   */
  @PostMapping("/{id}/complete")
  public ApiResponse<OrderDto> complete(@PathVariable Long id) {
    Authentication authentication =
        SecurityContextHolder.getContext().getAuthentication();
    Long callerId = (Long) authentication.getPrincipal();
    boolean admin = authentication.getAuthorities().stream()
        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    return ApiResponse.ok(orderService.complete(callerId, admin, id));
  }

  /**
   * Refunds a captured order (mock PSP — see {@code PaymentService}):
   * PAID → REFUNDED. Only the listing's seller (or an ADMIN) may refund —
   * the buyer cannot self-refund after capture (deliberate design decision,
   * see the service javadoc); anything but PAID → 422, and a re-refund of
   * an already-REFUNDED order is idempotent. The listing flips
   * RESERVED → AVAILABLE in the same transaction, and a {@code @Version}
   * conflict surfaces as 409.
   */
  @PostMapping("/{id}/refund")
  public ApiResponse<OrderDto> refund(@PathVariable Long id) {
    Authentication authentication =
        SecurityContextHolder.getContext().getAuthentication();
    Long callerId = (Long) authentication.getPrincipal();
    boolean admin = authentication.getAuthorities().stream()
        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    return ApiResponse.ok(orderService.refund(callerId, admin, id));
  }
}
