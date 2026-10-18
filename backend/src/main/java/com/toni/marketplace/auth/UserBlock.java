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
 * One user blocking another (backlog #187, Flyway V47).
 *
 * <p>User references are plain ids (not {@code @ManyToOne}), mirroring
 * {@code Favorite} / {@code Message}: enforcement is an exact-pair
 * existence check and the list read is a DTO projection with an ad-hoc
 * join, never association fetching. Referential integrity is enforced
 * by the V47 foreign keys, not by the ORM. The {@code (blocker_id,
 * blocked_id)} unique pair is the storage-level half of the block
 * endpoint's idempotency.
 */
@Entity
@Table(name = "user_block",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_user_block_pair", columnNames = {"blocker_id", "blocked_id"}))
public class UserBlock {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "blocker_id", nullable = false)
  private Long blockerId;

  @Column(name = "blocked_id", nullable = false)
  private Long blockedId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected UserBlock() {}

  public UserBlock(Long blockerId, Long blockedId) {
    this.blockerId = blockerId;
    this.blockedId = blockedId;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public Long getBlockerId() { return blockerId; }
  public Long getBlockedId() { return blockedId; }
  public Instant getCreatedAt() { return createdAt; }
}
