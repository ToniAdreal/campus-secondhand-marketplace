package com.toni.marketplace.review;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerReviewRepository extends JpaRepository<SellerReview, Long> {

  /**
   * The review of one order, if it exists — the lookup behind "a second
   * review of the same order is 409" (backlog #175). The
   * {@code uq_review_order} unique key is the database-level arbiter
   * under races; this read is the friendly path.
   */
  Optional<SellerReview> findByOrderId(Long orderId);

  /** One seller's reviews; the service supplies the newest-first sort. */
  Page<SellerReview> findBySellerId(Long sellerId, Pageable pageable);

  /**
   * One row of a seller's rating aggregate: the mean rating and the
   * review count. The average is computed by the database from the
   * review rows on every read — never stored — so it cannot drift
   * from the rows it summarises (backlog #175).
   */
  interface RatingAggregate {
    /** Mean rating, or null when the seller has no reviews. */
    Double getAverageRating();

    long getReviewCount();
  }

  @Query("select avg(r.rating) as averageRating, count(r) as reviewCount "
      + "from SellerReview r where r.sellerId = :sellerId")
  RatingAggregate summarizeBySellerId(@Param("sellerId") Long sellerId);
}
