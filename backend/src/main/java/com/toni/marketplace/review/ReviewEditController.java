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
 * Buyer review edits (backlog #254). Authenticated like every order
 * route (the security chain's anyRequest rule; anonymous callers get
 * the 401 envelope, pinned by the sweep's "PUT /api/reviews/{id}"
 * entry); the buyer scoping lives in {@link ReviewService}. This
 * controller is deliberately separate from {@link ReviewController}:
 * the edit is addressed by review id, not by order id, and it is a
 * write on an existing row, not a creation. PUT, not PATCH, because
 * the edit is a full replacement of the rating and body — the same
 * bounds as create, so no partial-update semantics are invented here.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "orders", description = "Buyer orders and the mock-PSP payment lifecycle")
@RequestMapping("/api/reviews")
public class ReviewEditController {

  private final ReviewService reviewService;

  public ReviewEditController(ReviewService reviewService) {
    this.reviewService = reviewService;
  }

  /**
   * Replaces the rating and body of the caller's own review: buyer
   * only, inside {@code app.reviews.edit-window} (default 15 minutes
   * from creation, boundary inclusive), and only while the seller has
   * not responded — a response freezes the review with 422, so a
   * reply never ends up answering a changed review. The aggregate
   * follows the edited rating on the next read; nothing is stored.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "the edited review (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
          description = "validation failure — rating missing or outside 1-5, text over 500 characters (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not the review's buyer (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown review id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "edit window closed, or the seller has already responded (envelope)")})
  @PutMapping("/{id}")
  public ApiResponse<SellerReviewDto> edit(@PathVariable Long id,
                                           @Valid @RequestBody ReviewEditRequest request) {
    Long buyerId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(
        reviewService.editReview(buyerId, id, request.rating(), request.body()));
  }
}
