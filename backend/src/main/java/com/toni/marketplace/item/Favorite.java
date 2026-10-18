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

  /**
   * The listing's price when this save was made (backlog #189, Flyway
   * V49) — the baseline the price-drop fan-out in {@code ItemService}
   * compares edits against. It advances to the new price whenever a
   * drop is notified (or baselined), so a second drop notifies again
   * while a repeat edit at the same price stays silent. Nullable only
   * defensively: V49 backfills every pre-existing row from the item's
   * price at migration time and application writes always carry one.
   */
  @Column(name = "price_snapshot_cents")
  private Long priceSnapshotCents;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Favorite() {}

  public Favorite(Long userId, Long itemId, Long priceSnapshotCents) {
    this.userId = userId;
    this.itemId = itemId;
    this.priceSnapshotCents = priceSnapshotCents;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public Long getItemId() { return itemId; }
  public Long getPriceSnapshotCents() { return priceSnapshotCents; }
  public Instant getCreatedAt() { return createdAt; }

  /**
   * Moves the price baseline to {@code newPriceCents} (backlog #189):
   * called by the price-drop fan-out in the edit's own transaction,
   * after the drop it describes has been notified (or silently
   * baselined) — never on a rise.
   */
  public void advancePriceSnapshot(long newPriceCents) {
    this.priceSnapshotCents = newPriceCents;
  }
}
