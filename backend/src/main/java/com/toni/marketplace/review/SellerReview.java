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
 * <p>Reviews are append-only in v1 — there is deliberately no update
 * or delete path, no status, and no {@code @Version}: a row is written
 * once and never mutated, mirroring the audit log's discipline. The
 * {@code uq_review_order} unique key is the "one review per trade"
 * rule. Abuse handling stays with the #160 listing-report flow; no
 * moderation surface is built here.
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
  public Instant getCreatedAt() { return createdAt; }
}
