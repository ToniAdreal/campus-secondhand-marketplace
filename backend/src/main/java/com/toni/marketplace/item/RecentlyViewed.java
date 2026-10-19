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
 * One user's most recent detail view of one listing (backlog #195,
 * Flyway V53) — the browsing trail behind "Recently viewed", distinct
 * from {@link Favorite}: a favorite is a deliberate save, this row is
 * written automatically by the detail read.
 *
 * <p>User and listing references are plain ids (not {@code @ManyToOne})
 * on purpose, mirroring {@link Favorite}: the read path is a DTO
 * projection with ad-hoc joins, never association fetching, so the
 * entity stays a flat row. Referential integrity is enforced by the
 * V53 foreign keys, not by the ORM. The {@code (user_id, item_id)}
 * unique pair is the storage-level half of the record step's upsert:
 * a repeat view touches {@link #viewedAt} on the existing row instead
 * of inserting a second one.
 */
@Entity
@Table(name = "recently_viewed",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_recently_viewed_user_item", columnNames = {"user_id", "item_id"}))
public class RecentlyViewed {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "item_id", nullable = false)
  private Long itemId;

  /**
   * When this listing was last viewed by this user. Mutable by design
   * (unlike a favorite's {@code createdAt}): a repeat view moves the
   * row to the front of the trail, and the retention purge judges the
   * row by this column — a listing the owner keeps revisiting stays
   * fresh.
   */
  @Column(name = "viewed_at", nullable = false)
  private Instant viewedAt;

  protected RecentlyViewed() {}

  public RecentlyViewed(Long userId, Long itemId, Instant viewedAt) {
    this.userId = userId;
    this.itemId = itemId;
    this.viewedAt = viewedAt;
  }

  @PrePersist
  void onCreate() {
    if (this.viewedAt == null) {
      this.viewedAt = Instant.now();
    }
  }

  /** Moves this trail row to "now" — a repeat view of the same listing. */
  public void touch(Instant when) {
    this.viewedAt = when;
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public Long getItemId() { return itemId; }
  public Instant getViewedAt() { return viewedAt; }
}
