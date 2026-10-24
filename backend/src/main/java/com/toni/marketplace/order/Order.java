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
   * The mock PSP's capture reference for this order (backlog #114): the
   * {@code cap_mock_*} id {@code PaymentService.capture} returned, stored
   * by {@code OrderService.pay} in the same transaction as the
   * PENDING → PAID transition, so a paid order can always name the PSP
   * reference a reconciliation would look it up by. Distinct from
   * {@code captureIdempotencyKey} — that is the key the caller sent,
   * this is the id the PSP minted. Nullable until the order is paid; a
   * declined capture stores nothing.
   */
  @Column(name = "capture_id", length = 64)
  private String captureId;

  /**
   * The mock PSP's refund reference for this order (backlog #114): the
   * {@code rfd_mock_*} id {@code PaymentService.refund} returned, stored
   * by {@code OrderService.refund} in the same transaction as the
   * PAID → REFUNDED transition. Nullable until the order is refunded.
   */
  @Column(name = "refund_id", length = 64)
  private String refundId;

  /**
   * Cumulative amount refunded so far, in cents (backlog #210, Flyway
   * V59). Every refund path (seller/ADMIN refund, dispute resolution)
   * adds its amount here in the same transaction as the PSP call; the
   * invariant is {@code 0 <= refundedAmountCents <= amountCents}, and
   * the status flips to REFUNDED exactly when the cumulative total
   * reaches {@code amountCents}. A partial refund leaves the order
   * PAID and the listing RESERVED. The ADMIN settlement summary sums
   * this column — not the status — so a partially refunded PAID order
   * reconciles.
   */
  @Column(name = "refunded_amount_cents", nullable = false)
  private long refundedAmountCents;

  /**
   * How many refunds have reached the PSP seam for this order
   * (backlog #210). Doubles as the sequence the per-refund idempotency
   * key is derived from ({@code rfd_<orderId>_<seq>}): the count only
   * moves at the commit that records the refund, so a crash-and-retry
   * before that commit re-derives the same key (PSP replay, no second
   * refund) and the next distinct refund derives a fresh, never-reused
   * key.
   */
  @Column(name = "refund_count", nullable = false)
  private int refundCount;

  /**
   * SHA-256 hash of the buyer's 6-digit handover code (backlog #179,
   * Flyway V43). Minted by {@code OrderService.pay} in the same
   * transaction as the PENDING → PAID transition; the plaintext is
   * returned to the buyer exactly once (the pay response, or the
   * regenerate response) and is never stored, so a lost code can only
   * be replaced, never recovered. {@code OrderService.complete} requires
   * a seller caller to present the code; an ADMIN completion is an
   * explicit override. Nullable until the order is paid (and for orders
   * paid before #179 landed — the buyer mints one via the regenerate
   * endpoint).
   */
  @Column(name = "handover_code_hash", length = 64)
  private String handoverCodeHash;

  /**
   * Consecutive failed handover-code presentations at the complete
   * exchange (backlog #179), mirroring the #60/#101 lockout shape.
   * Incremented in a REQUIRES_NEW transaction by
   * {@code HandoverAttemptService} so the 400 rollback cannot erase the
   * count; reset by a successful completion or a buyer regeneration.
   */
  @Column(name = "handover_failed_attempts", nullable = false)
  private int handoverFailedAttempts;

  /**
   * While this instant is in the future the handover code is locked:
   * even the correct code is refused at complete with the identical
   * 400. Set when {@link #handoverFailedAttempts} reaches
   * {@code app.orders.handover.max-attempts}; an expired lock resets
   * the counter on the next attempt. The lock gates only the complete
   * exchange — never the buyer's dispute path.
   */
  @Column(name = "handover_locked_until")
  private Instant handoverLockedUntil;

  /**
   * When the order became PAID (backlog #180, Flyway V44), stamped by
   * {@code OrderService.pay} from the injected Clock in the same
   * transaction as the PENDING → PAID transition. Nullable until paid;
   * also null for orders paid before V44 landed — their transition
   * instant was never recorded and is not backfilled from
   * {@code updatedAt} (that would fabricate a receipt timestamp).
   */
  @Column(name = "paid_at")
  private Instant paidAt;

  /**
   * When the order became COMPLETED (backlog #180, Flyway V44), stamped
   * by {@code OrderService.complete} in the same transaction as the
   * PAID → COMPLETED transition. Nullable until completed (and for
   * orders completed before V44, as on {@link #paidAt}).
   */
  @Column(name = "completed_at")
  private Instant completedAt;

  /**
   * Carrier the seller named when shipping the order (backlog #222,
   * Flyway V66). Nullable until the order is shipped; blank values are
   * rejected at the ship endpoint, so a stored value is always
   * non-blank. Shown to the buyer on their order view — it is
   * seller-entered free text, not a verified carrier integration.
   */
  @Column(name = "carrier", length = 64)
  private String carrier;

  /**
   * Tracking number the seller recorded at ship time (backlog #222,
   * Flyway V66). Nullable until shipped; shown verbatim to the buyer.
   * No carrier API is called — there is no tracking-status lookup in
   * this project, only the number the seller typed.
   */
  @Column(name = "tracking_number", length = 128)
  private String trackingNumber;

  /**
   * When the order became SHIPPED (backlog #222, Flyway V66), stamped
   * by {@code OrderService.ship} from the injected Clock in the same
   * transaction as the PAID → SHIPPED transition. Nullable until
   * shipped.
   */
  @Column(name = "shipped_at")
  private Instant shippedAt;

  /**
   * Read-only view of the DB-generated guard column behind the
   * {@code uq_order_active_item} unique constraint: equals the item id while
   * the order is active (PENDING/PAID/SHIPPED/DISPUTED), NULL once terminal.
   * insertable and updatable are false — the database computes it, never
   * the application.
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
  public String getCaptureId() { return captureId; }
  public void setCaptureId(String captureId) { this.captureId = captureId; }
  public String getRefundId() { return refundId; }
  public void setRefundId(String refundId) { this.refundId = refundId; }
  public long getRefundedAmountCents() { return refundedAmountCents; }
  public void setRefundedAmountCents(long refundedAmountCents) {
    this.refundedAmountCents = refundedAmountCents;
  }
  public int getRefundCount() { return refundCount; }
  public void setRefundCount(int refundCount) { this.refundCount = refundCount; }
  public String getHandoverCodeHash() { return handoverCodeHash; }
  public void setHandoverCodeHash(String handoverCodeHash) {
    this.handoverCodeHash = handoverCodeHash;
  }
  public int getHandoverFailedAttempts() { return handoverFailedAttempts; }
  public void setHandoverFailedAttempts(int handoverFailedAttempts) {
    this.handoverFailedAttempts = handoverFailedAttempts;
  }
  public Instant getHandoverLockedUntil() { return handoverLockedUntil; }
  public Instant getPaidAt() { return paidAt; }
  public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
  public Instant getCompletedAt() { return completedAt; }
  public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
  public String getCarrier() { return carrier; }
  public void setCarrier(String carrier) { this.carrier = carrier; }
  public String getTrackingNumber() { return trackingNumber; }
  public void setTrackingNumber(String trackingNumber) { this.trackingNumber = trackingNumber; }
  public Instant getShippedAt() { return shippedAt; }
  public void setShippedAt(Instant shippedAt) { this.shippedAt = shippedAt; }
  public void setHandoverLockedUntil(Instant handoverLockedUntil) {
    this.handoverLockedUntil = handoverLockedUntil;
  }
  public Long getActiveItemId() { return activeItemId; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
