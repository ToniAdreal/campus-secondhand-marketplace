package com.toni.marketplace.review;

import com.toni.marketplace.common.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seller review reads (backlog #175): the paginated, newest-first list
 * behind the public seller profile (#174). Authenticated, exactly like
 * the profile; unknown or disabled sellers are 404, sharing the
 * profile's visibility gate in {@link ReviewService}.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "users", description = "Public seller profiles")
@RequestMapping("/api/users/{id}")
public class SellerReviewsController {

  private final ReviewService reviewService;

  public SellerReviewsController(ReviewService reviewService) {
    this.reviewService = reviewService;
  }

  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "the seller's reviews, newest first (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown seller, or a disabled account (envelope)")})
  @GetMapping("/reviews")
  public ApiResponse<Page<SellerReviewDto>> reviews(
      @PathVariable Long id,
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    return ApiResponse.ok(reviewService.listReviews(id, pageable));
  }
}
