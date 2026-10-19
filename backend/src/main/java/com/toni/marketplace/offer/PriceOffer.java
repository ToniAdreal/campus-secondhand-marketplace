package com.toni.marketplace.offer;

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
 * A buyer's proposed price for one listing (backlog #168, Flyway V34).
 * {@code buyerId} / {@code sellerId} reference {@code app_user} ids;
 * {@code sellerId} is denormalised from the listing at creation (a
 * listing's seller never changes, so the copy cannot drift) to keep the
 * seller's received-offers list a plain indexed read.
 *
 * <p>Concurrency contract, mirroring {@code Order}:
 * <ul>
 *   <li>{@code @Version} optimistic locking — a concurrent accept /
 *       decline / withdraw on the same offer fails fast instead of
 *       silently last-writer-wins; the loser is mapped to 409.</li>
 *   <li>The DB-generated {@code live_offer_key} guard column enforces
 *       "one live offer per (buyer, item)" at the database level —
 *       PENDING since V34, PENDING or COUNTERED since V52 — so two
 *       racing creates cannot both succeed even if the application
 *       check races, and a countered offer keeps holding its pair
 *       while the buyer decides.</li>
 * </ul>
 *
 * <p>{@code counterPriceCents} is set exactly once, by the seller's
 * counter transition (backlog #193, Flyway V51); the buyer's original
 * {@code priceCents} is never rewritten, so the row keeps the whole
 * negotiation. {@code orderId} is set exactly once, by an accept
 * transition (the seller accepting PENDING, or the buyer accepting
 * COUNTERED), to the order created at the agreed price; it stays null
 * on every other outcome — declining, withdrawing or expiring an offer
 * never creates an order and never touches the listing.
 */
@Entity
@Table(name = "price_offer")
public class PriceOffer {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "item_id", nullable = false)
  private Item item;

  @Column(name = "buyer_id", nullable = false)
  private Long buyerId;

  @Column(name = "seller_id", nullable = false)
  private Long sellerId;

  /** The proposed price in minor units (cents); what the buyer offers to pay. */
  @Column(name = "price_cents", nullable = false)
  private Long priceCents;

  /**
   * The seller's counter price in minor units (cents), set by the one
   * counter transition (backlog #193); null until (and unless) the
   * seller counters. The order a counter-acceptance creates is priced
   * at this value, never at {@code priceCents} or the asking price.
   */
  @Column(name = "counter_price_cents")
  private Long counterPriceCents;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 16)
  private OfferStatus status = OfferStatus.PENDING;

  /** The order the acceptance created; null until (and unless) accepted. */
  @Column(name = "order_id")
  private Long orderId;

  /** When a still-PENDING offer lapses to EXPIRED (app.offers.pending-ttl). */
  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  /** Optimistic locking: concurrent transitions on one offer fail fast. */
  @Version
  private Long version;

  /** Read-only view of the DB-generated one-live-offer guard column. */
  @Column(name = "live_offer_key", insertable = false, updatable = false)
  private String liveOfferKey;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PriceOffer() {}

  public PriceOffer(Item item, Long buyerId, Long sellerId, Long priceCents, Instant expiresAt) {
    this.item = item;
    this.buyerId = buyerId;
    this.sellerId = sellerId;
    this.priceCents = priceCents;
    this.expiresAt = expiresAt;
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
  public Long getSellerId() { return sellerId; }
  public Long getPriceCents() { return priceCents; }
  public Long getCounterPriceCents() { return counterPriceCents; }
  public void setCounterPriceCents(Long counterPriceCents) {
    this.counterPriceCents = counterPriceCents;
  }
  public OfferStatus getStatus() { return status; }
  public void setStatus(OfferStatus status) { this.status = status; }
  public Long getOrderId() { return orderId; }
  public void setOrderId(Long orderId) { this.orderId = orderId; }
  public Instant getExpiresAt() { return expiresAt; }
  public Long getVersion() { return version; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
