package com.toni.marketplace.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * One user following another user's listings (backlog #217, Flyway V63).
 *
 * <p>User references are plain ids (not {@code @ManyToOne}), mirroring
 * {@code UserBlock} / {@code Favorite}: the follow/unfollow writes are
 * exact-pair existence checks and the following-list read is a DTO
 * projection with an ad-hoc join, never association fetching. The
 * {@code (follower_id, seller_id)} unique pair is the storage-level half
 * of the follow endpoint's idempotency — a repeat follow of the same
 * seller is a no-op, and a concurrent double-follow that races the
 * existence check collapses onto this constraint instead of writing two
 * rows.
 *
 * <p>The follow is a notification tool, not a social graph: no follower
 * counts are ever exposed publicly (the notification row carries only
 * the listing, and the seller surface carries no counts).
 */
@Entity
@Table(name = "seller_follow",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_seller_follow_pair", columnNames = {"follower_id", "seller_id"}))
public class SellerFollow {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "follower_id", nullable = false)
  private Long followerId;

  @Column(name = "seller_id", nullable = false)
  private Long sellerId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected SellerFollow() {}

  public SellerFollow(Long followerId, Long sellerId) {
    this.followerId = followerId;
    this.sellerId = sellerId;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public Long getFollowerId() { return followerId; }
  public Long getSellerId() { return sellerId; }
  public Instant getCreatedAt() { return createdAt; }
}
