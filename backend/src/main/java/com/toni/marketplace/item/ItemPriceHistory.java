package com.toni.marketplace.item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One price a listing has carried (backlog #201, Flyway V56): the
 * creation price, plus one row for every edit that actually changed
 * {@code priceCents}. Append-only — rows are never updated, and status
 * flips / photo changes never append, so the trail is exactly the
 * listing's price timeline and nothing else.
 *
 * <p>The listing reference is a plain id (not {@code @ManyToOne}) on
 * purpose, mirroring {@link Favorite} / {@link RecentlyViewed}: the
 * read path is a flat ordered query, never association fetching.
 * Referential integrity is enforced by the V56 foreign key (which
 * cascades on listing delete), not by the ORM. There is deliberately
 * no editor column: who changed a price is an audit-log concern
 * (#98), and the public history contract is pinned to price +
 * timestamp only (see {@link PriceHistoryPointDto}).
 */
@Entity
@Table(name = "price_history")
public class ItemPriceHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "item_id", nullable = false)
  private Long itemId;

  /** Price in minor units (cents), mirroring {@link Item#getPriceCents()}. */
  @Column(name = "price_cents", nullable = false)
  private Long priceCents;

  /** When this price took effect (creation time for the seed row). */
  @Column(name = "changed_at", nullable = false)
  private Instant changedAt;

  protected ItemPriceHistory() {}

  public ItemPriceHistory(Long itemId, Long priceCents, Instant changedAt) {
    this.itemId = itemId;
    this.priceCents = priceCents;
    this.changedAt = changedAt;
  }

  @PrePersist
  void onCreate() {
    if (this.changedAt == null) {
      this.changedAt = Instant.now();
    }
  }

  public Long getId() { return id; }
  public Long getItemId() { return itemId; }
  public Long getPriceCents() { return priceCents; }
  public Instant getChangedAt() { return changedAt; }
}
