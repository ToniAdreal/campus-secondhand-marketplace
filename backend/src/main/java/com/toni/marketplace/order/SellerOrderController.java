package com.toni.marketplace.order;

import com.toni.marketplace.common.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seller-facing order reads. Buyers see their own orders under
 * {@code /api/orders}; a seller needs the mirror view — the orders buyers
 * placed on <em>their</em> listings — so they can fulfill, ship, and answer
 * "who bought my item?" without this endpoint family being absent entirely.
 *
 * <p>Security posture mirrors {@link OrderController}: everything here
 * requires a valid Bearer access token (falls out of
 * {@code SecurityConfig}'s {@code anyRequest().authenticated()} — no rule
 * change was needed), anonymous callers get the JSON 401 envelope from the
 * entry point, and authorization is enforced in
 * {@link OrderService#getSellerOrderFor} / {@link OrderService#listSellerOrders}.
 */
@RestController
@RequestMapping("/api/seller/orders")
public class SellerOrderController {

  private final OrderService orderService;

  public SellerOrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  /**
   * Lists orders placed on the caller's listings, newest first. Default page
   * size 20, sorted by {@code createdAt} desc with {@code id} desc as the
   * tie-break; the client may override with {@code ?page=&size=&sort=}.
   * Strictly scoped to the caller's {@code sellerId} — no ADMIN bypass.
   */
  @GetMapping
  public ApiResponse<Page<OrderDto>> list(
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    Long sellerId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(orderService.listSellerOrders(sellerId, pageable));
  }

  /**
   * Reads one order on the caller's listing. The listing's seller — or an
   * ADMIN — may read; an order on someone else's listing gets 403; anonymous
   * callers get the JSON 401 envelope; unknown ids get 404.
   */
  @GetMapping("/{id}")
  public ApiResponse<OrderDto> get(@PathVariable Long id) {
    Authentication authentication =
        SecurityContextHolder.getContext().getAuthentication();
    Long callerId = (Long) authentication.getPrincipal();
    boolean admin = authentication.getAuthorities().stream()
        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    return ApiResponse.ok(orderService.getSellerOrderFor(callerId, admin, id));
  }
}
