package com.toni.marketplace.review;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Buyer review creation (backlog #175). Authenticated like every order
 * route (the security chain's anyRequest rule; anonymous callers get
 * the 401 envelope); the buyer scoping lives in {@link ReviewService}.
 * The seller-side read lives on {@link SellerReviewsController}.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "orders", description = "Buyer orders and the mock-PSP payment lifecycle")
@RequestMapping("/api/orders")
public class ReviewController {

  private final ReviewService reviewService;

  public ReviewController(ReviewService reviewService) {
    this.reviewService = reviewService;
  }

  /**
   * Reviews a COMPLETED order: one review per trade, buyer only. Any
   * other order status is 422; a second review of the same order is
   * 409. Reviews are append-only in v1 — there is no edit or delete.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "the created review (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
          description = "validation failure — rating missing or outside 1-5, text over 500 characters (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not the order's buyer (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown order id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
          description = "this order already has a review (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "order is not COMPLETED (envelope)")})
  @PostMapping("/{id}/review")
  public ApiResponse<SellerReviewDto> review(@PathVariable Long id,
                                             @Valid @RequestBody ReviewCreateRequest request) {
    Long buyerId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(
        reviewService.createReview(buyerId, id, request.rating(), request.body()));
  }
}
