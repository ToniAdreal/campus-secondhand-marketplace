package com.toni.marketplace.review;

import java.time.Instant;

/**
 * Public shape of a seller review (backlog #175): ids, the reviewer's
 * username (resolved at read time — the review row stores only the
 * buyer id), the rating, the optional text and when it was written.
 * No JPA types. {@code body} is null when the buyer left a star-only
 * review. Backlog #246 adds {@code sellerResponse}: the seller's
 * public reply, null until the seller answers — it rides this same
 * projection, so the profile, the review list and the #243 account
 * export all show the same reply without a second shape.
 */
public record SellerReviewDto(
    Long id,
    Long orderId,
    Long buyerId,
    String reviewerUsername,
    Long sellerId,
    int rating,
    String body,
    String sellerResponse,
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
        review.getSellerResponse(),
        review.getCreatedAt());
  }
}
