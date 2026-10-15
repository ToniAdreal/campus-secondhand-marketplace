package com.toni.marketplace.dispute;

import com.toni.marketplace.order.OrderStatus;
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
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One buyer's dispute of one order (backlog #169, Flyway V36).
 *
 * <p>Order/buyer/seller references are plain ids (not
 * {@code @ManyToOne}), mirroring {@code Notification} and the audit
 * log: the read path is the ADMIN queue's paginated projection, never
 * association fetching. {@code sellerId} is denormalised from the
 * listing at open time (a listing's seller never changes, so the copy
 * cannot drift).
 *
 * <p>{@code previousStatus} records the order status the dispute froze
 * (PAID or COMPLETED): a release resolution returns the order to
 * exactly that status, and a refund resolution uses it to explain the
 * listing outcome. {@code listingRestored} is written only by a refund
 * resolution — {@code true} when the listing flipped RESERVED →
 * AVAILABLE, {@code false} when it was already SOLD (a completed
 * handoff) and stayed SOLD; it stays null on OPEN and RELEASED rows.
 *
 * <p>Concurrency contract, mirroring {@code Order}: {@code @Version}
 * optimistic locking — two racing ADMIN resolutions on the same
 * dispute fail fast instead of both applying; the loser is mapped to
 * 409. The DB-generated {@code open_order_id} guard column enforces
 * "one OPEN dispute per order" at the database level.
 */
@Entity
@Table(name = "order_dispute")
public class OrderDispute {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "order_id", nullable = false)
  private Long orderId;

  @Column(name = "buyer_id", nullable = false)
  private Long buyerId;

  @Column(name = "seller_id", nullable = false)
  private Long sellerId;

  /** The buyer's reason, free text only (no evidence upload in v1), length-capped at the boundary. */
  @Column(nullable = false, length = 500)
  private String reason;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 24)
  private DisputeStatus status = DisputeStatus.OPEN;

  /** The order status this dispute froze: PAID or COMPLETED. */
  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "previous_status", nullable = false, length = 16)
  private OrderStatus previousStatus;

  /** Refund-resolution outcome for the listing; null until (and unless) refunded. */
  @Column(name = "listing_restored")
  private Boolean listingRestored;

  /** Optimistic locking: concurrent resolutions on one dispute fail fast. */
  @Version
  private Long version;

  /** Read-only view of the DB-generated one-open-dispute guard column. */
  @Column(name = "open_order_id", insertable = false, updatable = false)
  private Long openOrderId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** When an ADMIN resolved the dispute; null while OPEN. */
  @Column(name = "resolved_at")
  private Instant resolvedAt;

  protected OrderDispute() {}

  public OrderDispute(Long orderId, Long buyerId, Long sellerId, String reason,
                      OrderStatus previousStatus) {
    this.orderId = orderId;
    this.buyerId = buyerId;
    this.sellerId = sellerId;
    this.reason = reason;
    this.previousStatus = previousStatus;
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
  public Long getOrderId() { return orderId; }
  public Long getBuyerId() { return buyerId; }
  public Long getSellerId() { return sellerId; }
  public String getReason() { return reason; }
  public DisputeStatus getStatus() { return status; }
  public void setStatus(DisputeStatus status) { this.status = status; }
  public OrderStatus getPreviousStatus() { return previousStatus; }
  public Boolean getListingRestored() { return listingRestored; }
  public void setListingRestored(Boolean listingRestored) {
    this.listingRestored = listingRestored;
  }
  public Long getVersion() { return version; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public Instant getResolvedAt() { return resolvedAt; }
  public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
