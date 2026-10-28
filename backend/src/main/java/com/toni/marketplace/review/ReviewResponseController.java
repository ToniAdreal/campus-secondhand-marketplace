package com.toni.marketplace.review;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seller responses on reviews (backlog #246). Authenticated like
 * every order route (the security chain's anyRequest rule; anonymous
 * callers get the 401 envelope, pinned by the sweep's
 * "PUT /api/reviews/{id}/response" entry); the seller scoping lives
 * in {@link ReviewService}. This controller is deliberately separate
 * from {@link SellerReviewsController}: the response is addressed by
 * review id, not by seller id, and it is a write, not a list read.
 * PUT, not POST, because setting the response is idempotent — the
 * same body twice leaves the same single column, and an edit is the
 * same operation as the first response.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "users", description = "Public seller profiles")
@RequestMapping("/api/reviews")
public class ReviewResponseController {

  private final ReviewService reviewService;

  public ReviewResponseController(ReviewService reviewService) {
    this.reviewService = reviewService;
  }

  /**
   * Sets or replaces the seller's public response on one review: the
   * review's seller only. The response renders under the review on
   * the public profile and on the seller's review list; it never
   * touches the rating or the aggregate.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "the review with its response (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
          description = "validation failure — blank response or over 1000 characters (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not the review's seller (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown review id (envelope)")})
  @PutMapping("/{id}/response")
  public ApiResponse<SellerReviewDto> respond(@PathVariable Long id,
                                              @Valid @RequestBody ReviewResponseRequest request) {
    Long sellerId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(
        reviewService.respondToReview(sellerId, id, request.response()));
  }
}
