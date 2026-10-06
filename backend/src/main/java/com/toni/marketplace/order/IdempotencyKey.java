package com.toni.marketplace.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row per (user, Idempotency-Key) for {@code POST /api/orders}.
 * {@code userId} and {@code orderId} reference {@code app_user.id} and
 * {@code orders.id} as plain ids (see the auth and order packages) — the FKs
 * live in Flyway V5; the entity stays association-free so replay lookups are
 * cheap single-row selects.
 */
@Entity
@Table(name = "idempotency_key",
    uniqueConstraints = @UniqueConstraint(name = "uq_idem_user_key",
        columnNames = {"user_id", "idempotency_key"}))
public class IdempotencyKey {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  /** Client-generated request id, at most 64 chars, scoped per user. */
  @Column(name = "idempotency_key", nullable = false, length = 64)
  private String idempotencyKey;

  /** Payload fingerprint: the item the key was first used for. */
  @Column(name = "item_id", nullable = false)
  private Long itemId;

  /** Set once the order is created; null while IN_PROGRESS or FAILED. */
  @Column(name = "order_id")
  private Long orderId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 16)
  private IdempotencyStatus status = IdempotencyStatus.IN_PROGRESS;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected IdempotencyKey() {}

  public IdempotencyKey(Long userId, String idempotencyKey, Long itemId) {
    this.userId = userId;
    this.idempotencyKey = idempotencyKey;
    this.itemId = itemId;
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }

  public void complete(Long orderId) {
    this.orderId = orderId;
    this.status = IdempotencyStatus.COMPLETED;
  }

  public void fail() {
    this.status = IdempotencyStatus.FAILED;
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public Long getItemId() { return itemId; }
  public Long getOrderId() { return orderId; }
  public IdempotencyStatus getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
