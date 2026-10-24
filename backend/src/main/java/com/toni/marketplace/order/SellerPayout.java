package com.toni.marketplace.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One seller's payout for one completed order (backlog #221, Flyway V65).
 * Created in the completion transaction by
 * {@link SellerPayoutService#createForCompletedOrder(Order)} — exactly one
 * row per order, enforced by the {@code uq_seller_payout_order} unique
 * constraint plus the creation guard (a re-complete returns early and
 * never reaches creation; two racing completions collapse on the order
 * row's {@code @Version} first).
 *
 * <p>Money semantics, stated plainly because the mock takes no fee:
 * {@code amountCents} is the order's captured amount minus its cumulative
 * refunds at completion — no platform fee is invented or deducted. The
 * mock PSP pays nobody, so {@code PAID} here means "the sweep advanced
 * the ledger state", never "money moved to a bank account".
 *
 * <p>References are plain ids, not JPA associations, mirroring
 * {@code OrderDispute} and {@code Notification}: the read path is the
 * seller's paginated list, never association fetching. There is
 * deliberately no foreign key to {@code orders} — the ledger must survive
 * the order row it records (and existing test cleanups delete orders
 * without knowing about payouts). A later refund never mutates this row:
 * it writes a {@link SellerPayoutAdjustment} instead (ledger, not a
 * balance column).
 */
@Entity
@Table(name = "seller_payout")
public class SellerPayout {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "order_id", nullable = false, unique = true)
  private Long orderId;

  @Column(name = "seller_id", nullable = false)
  private Long sellerId;

  /** Net amount owed at completion, in cents (captured minus refunded). */
  @Column(name = "amount_cents", nullable = false)
  private long amountCents;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 16)
  private PayoutStatus status = PayoutStatus.PENDING;

  /** When the row became PAYABLE (the sweep's promotion stamp). */
  @Column(name = "payable_at")
  private Instant payableAt;

  /** When the row became PAID (the sweep's payout stamp). */
  @Column(name = "paid_at")
  private Instant paidAt;

  /** Optimistic locking: concurrent sweep transitions fail fast. */
  @Version
  private Long version;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected SellerPayout() {}

  public SellerPayout(Long orderId, Long sellerId, long amountCents) {
    this.orderId = orderId;
    this.sellerId = sellerId;
    this.amountCents = amountCents;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public Long getOrderId() { return orderId; }
  public Long getSellerId() { return sellerId; }
  public long getAmountCents() { return amountCents; }
  public PayoutStatus getStatus() { return status; }
  public void setStatus(PayoutStatus status) { this.status = status; }
  public Instant getPayableAt() { return payableAt; }
  public void setPayableAt(Instant payableAt) { this.payableAt = payableAt; }
  public Instant getPaidAt() { return paidAt; }
  public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
  public Long getVersion() { return version; }
  public Instant getCreatedAt() { return createdAt; }
}
