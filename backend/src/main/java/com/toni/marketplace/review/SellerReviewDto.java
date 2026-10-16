package com.toni.marketplace.review;

import java.time.Instant;

/**
 * Public shape of a seller review (backlog #175): ids, the reviewer's
 * username (resolved at read time — the review row stores only the
 * buyer id), the rating, the optional text and when it was written.
 * No JPA types. {@code body} is null when the buyer left a star-only
 * review.
 */
public record SellerReviewDto(
    Long id,
    Long orderId,
    Long buyerId,
    String reviewerUsername,
    Long sellerId,
    int rating,
    String body,
    Instant createdAt) {

  public static SellerReviewDto from(SellerReview review, String reviewerUsername) {
    return new SellerReviewDto(
        review.getId(),
        review.getOrderId(),
        review.getBuyerId(),
        reviewerUsername,
        review.getSellerId(),
        review.getRating(),
        review.getBody(),
        review.getCreatedAt());
  }
}
