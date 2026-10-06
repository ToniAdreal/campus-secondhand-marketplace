package com.toni.marketplace.order;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.context.SecurityContextHolder;
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
