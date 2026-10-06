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
}
