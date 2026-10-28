package com.toni.marketplace.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One buyer's review of one completed trade (backlog #175, Flyway V40).
 *
 * <p>Order/buyer/seller references are plain ids (not
 * {@code @ManyToOne}), mirroring {@code OrderDispute} and the audit log:
 * the read paths are the seller-page list and the rating aggregate,
 * never association fetching. {@code sellerId} is denormalised from the
 * listing at write time (a listing's seller never changes, so the copy
 * cannot drift).
 *
 * <p>Reviews are append-only except for the two sanctioned mutations,
 * and there is no delete path, no status, and no {@code @Version}.
 * The {@code uq_review_order} unique key is the "one review per trade"
 * rule. Abuse handling stays with the #160 listing-report flow; no
 * moderation surface is built here.
 *
 * <p>The sanctioned mutations are the seller response (backlog
 * #246): {@code sellerResponse} is the seller's public reply, set and
 * re-set through {@link ReviewService#respondToReview} only. It lives
 * in a single nullable column, so one review can never carry two
 * responses, and it is never part of the rating aggregate — and the
 * buyer edit (backlog #254): the rating and body, set through
 * {@link ReviewService#editReview} only, inside the edit window and
 * only while no seller response has landed. There is deliberately no
 * edit history and no {@code editedAt} column: an edit overwrites the
 * two columns, {@code createdAt} never moves, and the aggregate —
 * computed from the rows on every read — follows the edited rating
 * with nothing to resync.
 */
@Entity
@Table(name = "seller_review")
public class SellerReview {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "order_id", nullable = false)
  private Long orderId;

  /** The reviewer: the order's buyer, enforced in the service. */
  @Column(name = "buyer_id", nullable = false)
  private Long buyerId;

  @Column(name = "seller_id", nullable = false)
  private Long sellerId;

  /** 1..5 stars, validated at the API boundary. */
  @Column(nullable = false)
  private int rating;

  /** Optional free text, length-capped at the boundary; null when the buyer left none. */
  @Column(length = 500)
  private String body;

  /**
   * The seller's public response (backlog #246, Flyway V73): null
   * until the seller answers, then the trimmed response text
   * (length-capped at the boundary, like {@code body}). Editing the
   * response overwrites this column — there is deliberately no
   * response history table, so the profile always shows the current
   * reply and nothing else.
   */
  @Column(name = "seller_response", length = 1000)
  private String sellerResponse;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected SellerReview() {}

  public SellerReview(Long orderId, Long buyerId, Long sellerId, int rating, String body) {
    this.orderId = orderId;
    this.buyerId = buyerId;
    this.sellerId = sellerId;
    this.rating = rating;
    this.body = body;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public Long getOrderId() { return orderId; }
  public Long getBuyerId() { return buyerId; }
  public Long getSellerId() { return sellerId; }
  public int getRating() { return rating; }
  public String getBody() { return body; }
  public String getSellerResponse() { return sellerResponse; }
  public Instant getCreatedAt() { return createdAt; }

  /** The #246 mutation: only ReviewService calls this, after the seller check. */
  public void setSellerResponse(String sellerResponse) {
    this.sellerResponse = sellerResponse;
  }

  /** The #254 mutations: only ReviewService calls these, after the buyer + window checks. */
  public void setRating(int rating) {
    this.rating = rating;
  }

  public void setBody(String body) {
    this.body = body;
  }
}
