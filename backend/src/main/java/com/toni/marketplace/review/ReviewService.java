package com.toni.marketplace.review;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditService;
import com.toni.marketplace.audit.AuditTargetType;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.order.Order;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Post-completion seller reviews (backlog #175): completed trades leave
 * a trust signal the public seller profile (#174) can display.
 *
 * <p>Create rules ({@link #createReview}):
 * <ul>
 *   <li>Buyer only — anyone else gets 403, checked before any state
 *       guard, mirroring {@code DisputeService}, so an order id never
 *       leaks what the caller may do with it. Unknown order → 404;
 *       anonymous → the chain's 401 envelope.</li>
 *   <li>COMPLETED orders only — any other status is 422. A PAID order
 *       is not yet a completed trade; a DISPUTED one is frozen.</li>
 *   <li>One review per trade: a second review of the same order is
 *       409. The V40 {@code uq_review_order} unique key is the
 *       database-level arbiter under races.</li>
 *   <li>The seller is copied from the listing at write time; the
 *       rating is 1–5 and the text optional (blank normalises to null),
 *       both validated at the controller boundary.</li>
 * </ul>
 *
 * <p>Read rules: a seller's reviews are paginated, newest first. The
 * seller-visibility gate mirrors #174 exactly — an unknown id is 404
 * and a disabled account reads as 404 — so the reviews can never
 * disagree with the profile about whether the seller exists. The
 * aggregate (average + count) is computed from the rows on every read
 * (see {@link SellerReviewRepository#summarizeBySellerId}); the
 * profile folds it in, nothing stores an average.
 *
 * <p>No delete, no moderation surface — the #160 report flow remains
 * the abuse path.
 *
 * <p>Seller responses (backlog #246) are one sanctioned mutation, and
 * only for the response column: {@link #respondToReview} lets the
 * review's seller set or replace the public reply. Seller only —
 * anyone else gets 403, checked after the 404 so a review id never
 * leaks what the caller may do with it, mirroring
 * {@code createReview}'s order. The response is trimmed at write time
 * (blank is rejected at the boundary, like the #240 dispute note);
 * the rating and body are never touched here, and the aggregate reads
 * the rating column alone, so a response cannot move a seller's
 * average.
 *
 * <p>Buyer edits (backlog #254) are the other sanctioned mutation:
 * {@link #editReview} lets the review's buyer replace the rating and
 * body inside {@code app.reviews.edit-window} (default 15 minutes from
 * {@code createdAt}, boundary inclusive, mirroring #206's message
 * edit). The aggregate is computed from the rows on every read, so an
 * edited rating moves the seller's average with nothing to resync.
 * Once the seller has responded, edits stop with 422 even inside the
 * window — a response must never end up answering a changed review.
 * The window and the response guard are both 422; the response guard
 * is checked first, mirroring #206's deleted-before-window order.
 *
 * <p>Audit (backlog #390): create, edit and respond each write one row
 * to the append-only trail from inside the transition's own
 * transaction ({@code REVIEW_CREATED} / {@code REVIEW_EDITED} /
 * {@code REVIEW_RESPONDED}, actor is the acting buyer/seller, target
 * is the review row) — a review is public reputation content, so the
 * trail is the record of who said what about whom and when. An
 * identical re-response writes no second row: the PUT is idempotent
 * by design, so the same text twice is no transition at all.
 */
@Service
public class ReviewService {

  private final SellerReviewRepository reviews;
  private final OrderRepository orders;
  private final UserRepository users;
  private final ReviewProperties reviewProps;
  private final Clock clock;
  private final AuditService audit;

  @Autowired
  public ReviewService(SellerReviewRepository reviews, OrderRepository orders,
                       UserRepository users, ReviewProperties reviewProps,
                       Clock clock, AuditService audit) {
    this.reviews = reviews;
    this.orders = orders;
    this.users = users;
    this.reviewProps = reviewProps;
    this.clock = clock;
    this.audit = audit;
  }

  /** Direct-construction constructor for body-logic unit tests (no audit trail). */
  public ReviewService(SellerReviewRepository reviews, OrderRepository orders,
                       UserRepository users, ReviewProperties reviewProps,
                       Clock clock) {
    this(reviews, orders, users, reviewProps, clock, AuditService.noop());
  }

  /** The audit trail, or the no-op instance for direct-construction tests. */
  private AuditService audit() {
    return audit != null ? audit : AuditService.noop();
  }

  /** Clock with a system fallback, mirroring MessageService's pattern. */
  private Clock clock() {
    return clock != null ? clock : Clock.systemUTC();
  }

  /** Edit window with the documented 15-minute default as fallback. */
  private Duration editWindow() {
    return reviewProps != null && reviewProps.getEditWindow() != null
        ? reviewProps.getEditWindow() : Duration.ofMinutes(15);
  }

  @Transactional
  public SellerReviewDto createReview(Long buyerId, Long orderId, Integer rating,
                                      String body) {
    Order order = orders.findById(orderId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "order not found"));
    if (!order.getBuyerId().equals(buyerId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "only the buyer can review this order");
    }
    if (order.getStatus() != OrderStatus.COMPLETED) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "order is " + order.getStatus() + ", only COMPLETED orders can be reviewed");
    }
    if (reviews.findByOrderId(orderId).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "this order already has a review");
    }
    String text = body == null || body.isBlank() ? null : body.trim();
    try {
      SellerReview review = new SellerReview(orderId, buyerId,
          order.getItem().getSellerId(), rating, text);
      SellerReviewDto dto = SellerReviewDto.from(reviews.save(review), usernameOf(buyerId));
      reviews.flush();
      // Audit inside the same transaction (backlog #390): the row and
      // the review commit or roll back together, and the 409 guard
      // above means a second create never reaches this call.
      audit().record(buyerId, AuditAction.REVIEW_CREATED, AuditTargetType.REVIEW,
          review.getId());
      return dto;
    } catch (DataIntegrityViolationException e) {
      // Lost the uq_review_order race: a concurrent create inserted the
      // order's one review first — the same client outcome as the
      // friendly check above.
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "this order already has a review");
    }
  }

  /**
   * Sets (or replaces) the seller's public response on one review.
   * Unknown review → 404; anyone but the review's seller → 403. The
   * response overwrites the single column — there is no second row
   * and no history, so "one response per review" holds structurally.
   */
  @Transactional
  public SellerReviewDto respondToReview(Long sellerId, Long reviewId, String response) {
    SellerReview review = reviews.findById(reviewId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "review not found"));
    if (!review.getSellerId().equals(sellerId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "only the seller can respond to this review");
    }
    String trimmed = response == null ? null : response.trim();
    if (Objects.equals(trimmed, review.getSellerResponse())) {
      // Identical re-response (backlog #390): the response is a single
      // column and the PUT is idempotent by design, so the same text
      // twice is no transition and writes no second audit row.
      return SellerReviewDto.from(review, usernameOf(review.getBuyerId()));
    }
    review.setSellerResponse(trimmed);
    SellerReviewDto dto = SellerReviewDto.from(reviews.save(review),
        usernameOf(review.getBuyerId()));
    // Audit inside the same transaction (backlog #390): a response with
    // different text is a new transition and writes a row.
    audit().record(sellerId, AuditAction.REVIEW_RESPONDED, AuditTargetType.REVIEW,
        review.getId());
    return dto;
  }

  /**
   * Edits one of the caller's own reviews (backlog #254).
   *
   * <ul>
   *   <li>404 — no review with that id (checked before ownership,
   *   mirroring {@code respondToReview}).</li>
   *   <li>403 — the caller is not the review's buyer. Only the buyer
   *   may edit; the seller, a stranger and ADMIN get no edit right —
   *   the seller's write path is the response, not this endpoint.</li>
   *   <li>422 — the seller has already responded, or the edit window
   *   ({@code app.reviews.edit-window}, default 15 minutes from
   *   {@code createdAt}) has closed. The boundary is inclusive: an
   *   edit landing exactly at {@code createdAt + window} is
   *   accepted, mirroring #206.</li>
   * </ul>
   *
   * <p>An edit replaces the rating and the body (blank body
   * normalises to null, like create) and leaves {@code createdAt}
   * untouched, so list order and the #175 pagination contract are
   * untouched. There is no edit history: the row simply carries the
   * new values, and the next aggregate read computes from them.
   */
  @Transactional
  public SellerReviewDto editReview(Long buyerId, Long reviewId, Integer rating,
                                    String body) {
    SellerReview review = reviews.findById(reviewId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "review not found"));
    if (!review.getBuyerId().equals(buyerId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "only the buyer can edit this review");
    }
    if (review.getSellerResponse() != null) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "this review already has a seller response and can no longer be edited");
    }
    Instant now = clock().instant();
    if (now.isAfter(review.getCreatedAt().plus(editWindow()))) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "the edit window for this review has closed");
    }
    review.setRating(rating);
    review.setBody(body == null || body.isBlank() ? null : body.trim());
    SellerReviewDto dto = SellerReviewDto.from(reviews.save(review),
        usernameOf(review.getBuyerId()));
    // Audit inside the same transaction (backlog #390): every successful
    // edit writes a row — the row itself carries no edit history, so the
    // trail is the only record that an earlier version existed.
    audit().record(buyerId, AuditAction.REVIEW_EDITED, AuditTargetType.REVIEW,
        review.getId());
    return dto;
  }

  /** One seller's reviews, paginated (the controller supplies newest-first). */
  @Transactional(readOnly = true)
  public Page<SellerReviewDto> listReviews(Long sellerId, Pageable pageable) {
    requireVisibleSeller(sellerId);
    Page<SellerReview> page = reviews.findBySellerId(sellerId, pageable);
    List<Long> buyerIds = page.getContent().stream().map(SellerReview::getBuyerId).toList();
    var usernames = new java.util.HashMap<Long, String>();
    users.findAllById(buyerIds).forEach(u -> usernames.put(u.getId(), u.getUsername()));
    return page.map(r -> SellerReviewDto.from(r, usernames.get(r.getBuyerId())));
  }

  /** The aggregate the public profile folds in: computed, never stored. */
  @Transactional(readOnly = true)
  public SellerReviewRepository.RatingAggregate aggregateFor(Long sellerId) {
    return reviews.summarizeBySellerId(sellerId);
  }

  private String usernameOf(Long userId) {
    return users.findById(userId).map(User::getUsername).orElse(null);
  }

  /** The #174 visibility gate, mirrored: unknown or disabled seller → 404. */
  private void requireVisibleSeller(Long sellerId) {
    User seller = users.findById(sellerId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "seller not found"));
    if (seller.isDisabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "seller not found");
    }
  }
}
