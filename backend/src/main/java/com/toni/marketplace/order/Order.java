package com.toni.marketplace.order;

import com.toni.marketplace.item.Item;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A buyer's purchase intent for one listing. {@code buyerId} references the
 * {@code app_user} id of the buyer (see the auth package). The price is
 * snapshotted at creation ({@code amountCents}) so later listing edits do
 * not rewrite what the buyer agreed to.
 *
 * Concurrency contract (the next backlog items build on it):
 * <ul>
 *   <li>{@code @Version} optimistic locking — concurrent state transitions
 *       on the same order fail fast with OptimisticLockException instead of
 *       silently last-writer-wins.</li>
 *   <li>The DB-generated {@code active_item_id} guard column (Flyway V4)
 *       enforces "one active order per item" at the database level, so two
 *       racing inserts cannot both succeed even if the application check
 *       races.</li>
 * </ul>
 */
@Entity
@Table(name = "orders")
public class Order {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "item_id", nullable = false)
  private Item item;

  @Column(name = "buyer_id", nullable = false)
  private Long buyerId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 16)
  private OrderStatus status = OrderStatus.PENDING;

  /** Listing price snapshot in minor units (cents) at order creation. */
  @Column(name = "amount_cents", nullable = false)
  private Long amountCents;

  /** Optimistic locking: concurrent transitions on one order fail fast. */
  @Version
  private Long version;

  /**
   * Order-scoped idempotency key for the PSP capture (backlog #65,
   * implements #45's declared follow-up). Generated once by
   * {@code OrderService.pay} when the order is first paid and reused by every
   * later capture call, so a retry after a crash between the PSP capture and
   * the commit replays against the same key instead of issuing a second
   * charge. Nullable for orders that have never been paid.
   */
  @Column(name = "capture_idempotency_key", length = 64)
  private String captureIdempotencyKey;

  /**
   * Order-scoped idempotency key for the PSP refund (backlog #65). Distinct
   * from the capture key — captures and refunds live in separate key
   * namespaces. Nullable until the order is refunded.
   */
  @Column(name = "refund_idempotency_key", length = 64)
  private String refundIdempotencyKey;

  /**
   * Read-only view of the DB-generated guard column behind the
   * {@code uq_order_active_item} unique constraint: equals the item id while
   * the order is active (PENDING/PAID), NULL once terminal. insertable and
   * updatable are false — the database computes it, never the application.
   */
  @Column(name = "active_item_id", insertable = false, updatable = false)
  private Long activeItemId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Order() {}

  public Order(Item item, Long buyerId, Long amountCents) {
    this.item = item;
    this.buyerId = buyerId;
    this.amountCents = amountCents;
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

  public Long getId() { return id; }
  public Item getItem() { return item; }
  public Long getBuyerId() { return buyerId; }
  public OrderStatus getStatus() { return status; }
  public void setStatus(OrderStatus status) { this.status = status; }
  public Long getAmountCents() { return amountCents; }
  public Long getVersion() { return version; }
  public String getCaptureIdempotencyKey() { return captureIdempotencyKey; }
  public void setCaptureIdempotencyKey(String captureIdempotencyKey) {
    this.captureIdempotencyKey = captureIdempotencyKey;
  }
  public String getRefundIdempotencyKey() { return refundIdempotencyKey; }
  public void setRefundIdempotencyKey(String refundIdempotencyKey) {
    this.refundIdempotencyKey = refundIdempotencyKey;
  }
  public Long getActiveItemId() { return activeItemId; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
