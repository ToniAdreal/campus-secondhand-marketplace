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
 * One user's saved browse filter set (backlog #164, Flyway V33).
 *
 * <p>The canonical filter set (#96) is stored as discrete columns —
 * keyword, categoryId, min/max price in cents, sort — never a serialized
 * query string, so the match check can re-run {@link ItemService}'s own
 * validation and browse projection over exactly these values. User and
 * category references are plain ids (not {@code @ManyToOne}), mirroring
 * {@link Favorite}: referential integrity for the owner is enforced by
 * the V33 foreign key, and {@code categoryId} deliberately carries no FK
 * (it is a filter value — a deleted category simply matches nothing).
 * A null filter column means "no filter" for that dimension, mirroring
 * the browse parameters.
 */
@Entity
@Table(name = "saved_search")
public class SavedSearch {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "keyword", length = 255)
  private String keyword;

  @Column(name = "category_id")
  private Long categoryId;

  @Column(name = "min_price_cents")
  private Long minPriceCents;

  @Column(name = "max_price_cents")
  private Long maxPriceCents;

  /** Canonical sort name from {@link ItemService#validatePriceRangeAndSort}. */
  @Column(name = "sort", nullable = false, length = 16)
  private String sort;

  /**
   * Background-matching watermark (backlog #182, Flyway V45): the
   * highest listing id the matcher has seen for this search. Only
   * listings with a greater id can ever be "new" for this search.
   * Null means "not baselined yet" (a row predating the matcher) —
   * the matcher's first run for such a row sets the baseline and
   * notifies nothing. Never exposed on {@link SavedSearchDto}: the
   * watermark is matcher bookkeeping, not user data.
   */
  @Column(name = "last_notified_item_id")
  private Long lastNotifiedItemId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected SavedSearch() {}

  public SavedSearch(Long userId, String keyword, Long categoryId,
                     Long minPriceCents, Long maxPriceCents, String sort) {
    this.userId = userId;
    this.keyword = keyword;
    this.categoryId = categoryId;
    this.minPriceCents = minPriceCents;
    this.maxPriceCents = maxPriceCents;
    this.sort = sort;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getKeyword() { return keyword; }
  public Long getCategoryId() { return categoryId; }
  public Long getMinPriceCents() { return minPriceCents; }
  public Long getMaxPriceCents() { return maxPriceCents; }
  public String getSort() { return sort; }
  public Long getLastNotifiedItemId() { return lastNotifiedItemId; }
  public Instant getCreatedAt() { return createdAt; }

  /**
   * Advances the match watermark (backlog #182). Monotonic by
   * construction: a stale or repeated matcher pass can never move it
   * backwards and re-notify listings that were already seen.
   */
  public void advanceLastNotifiedItemId(long seenItemId) {
    if (this.lastNotifiedItemId == null || seenItemId > this.lastNotifiedItemId) {
      this.lastNotifiedItemId = seenItemId;
    }
  }
}
