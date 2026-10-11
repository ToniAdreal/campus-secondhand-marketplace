package com.toni.marketplace.item;

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
 * One user's save ("favorite") of one listing (backlog #143, Flyway V29).
 *
 * <p>User and listing references are plain ids (not {@code @ManyToOne}) on
 * purpose, mirroring {@code Message}: the read path is a DTO projection
 * with ad-hoc joins, never association fetching, so the entity stays a
 * flat row. Referential integrity is enforced by the V29 foreign keys,
 * not by the ORM. The {@code (user_id, item_id)} unique pair is the
 * storage-level half of the save endpoint's idempotency.
 */
@Entity
@Table(name = "favorite",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_favorite_user_item", columnNames = {"user_id", "item_id"}))
public class Favorite {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "item_id", nullable = false)
  private Long itemId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Favorite() {}

  public Favorite(Long userId, Long itemId) {
    this.userId = userId;
    this.itemId = itemId;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public Long getItemId() { return itemId; }
  public Instant getCreatedAt() { return createdAt; }
}
