package com.toni.marketplace.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A compensating ledger entry against a {@link SellerPayout} (backlog
 * #221, Flyway V65). When money moves back after completion — in this
 * codebase that can only be a dispute resolution refunding the order
 * (seller refunds require a PAID order, so they can never target a
 * completed one) — the settlement row is never mutated: a negative entry
 * is appended here instead, and the seller's net is settlement plus
 * adjustments. {@code amountCents} is always negative on these rows;
 * the service enforces the sign at write time.
 *
 * <p>Plain ids again (see {@link SellerPayout}): the ledger outlives the
 * rows it references, and no foreign key may make a cleanup or a payout
 * delete cascade into it.
 */
@Entity
@Table(name = "seller_payout_adjustment")
public class SellerPayoutAdjustment {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "payout_id", nullable = false)
  private Long payoutId;

  @Column(name = "order_id", nullable = false)
  private Long orderId;

  /** Denormalised for the seller totals query (one aggregate, no join). */
  @Column(name = "seller_id", nullable = false)
  private Long sellerId;

  /** Negative: the refunded amount, as a ledger entry. */
  @Column(name = "amount_cents", nullable = false)
  private long amountCents;

  /** Why the entry exists, e.g. {@code DISPUTE_REFUND}. */
  @Column(nullable = false, length = 64)
  private String reason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected SellerPayoutAdjustment() {}

  public SellerPayoutAdjustment(Long payoutId, Long orderId, Long sellerId,
                                long amountCents, String reason) {
    this.payoutId = payoutId;
    this.orderId = orderId;
    this.sellerId = sellerId;
    this.amountCents = amountCents;
    this.reason = reason;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public Long getPayoutId() { return payoutId; }
  public Long getOrderId() { return orderId; }
  public Long getSellerId() { return sellerId; }
  public long getAmountCents() { return amountCents; }
  public String getReason() { return reason; }
  public Instant getCreatedAt() { return createdAt; }
}
