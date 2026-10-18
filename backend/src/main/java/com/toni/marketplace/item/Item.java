package com.toni.marketplace.item;

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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A second-hand listing. {@code sellerId} references the {@code app_user} id
 * of the seller (see the auth package).
 */
@Entity
@Table(name = "item")
public class Item {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 120)
  private String title;

  @Column(length = 2000)
  private String description;

  /** Price in minor units (cents) to avoid floating-point money. */
  @Column(name = "price_cents", nullable = false)
  private Long priceCents;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 16)
  private ItemStatus status = ItemStatus.AVAILABLE;

  @Column(name = "seller_id", nullable = false)
  private Long sellerId;

  /**
   * Optional category (Flyway V3 taxonomy). Nullable: listings created before
   * categories existed are simply uncategorized. Lazy — the list view maps
   * through it inside the service transaction.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "category_id")
  private Category category;

  /** Optimistic locking: concurrent buyers racing on one item fail fast. */
  @Version
  private Long version;

  /**
   * Public URL path of the listing's primary photo, e.g.
   * {@code /uploads/3f2a…​.png}, set by POST /api/items/{id}/photo. Nullable:
   * listings created before image upload existed have no photo. The stored
   * filename is a UUID (see ImageStorageService) — never the original client
   * filename, which is discarded at upload time.
   */
  @Column(name = "photo_url", length = 1024)
  private String photoUrl;

  /**
   * The listing's photo gallery (Flyway V15). Display order is the lowest
   * position first; the first row is the primary photo. Not a JPA
   * association on the read paths that matter — the detail query fetch-joins
   * it and the list view merges galleries in one batch query, so the
   * mapper never triggers lazy per-item SELECTs.
   */
  @OneToMany(mappedBy = "item", cascade = jakarta.persistence.CascadeType.ALL,
      orphanRemoval = true)
  @OrderBy("position ASC")
  private List<ItemPhoto> photos = new ArrayList<>();

  /**
   * Listing lifetime deadline (Flyway V41, backlog #177): an AVAILABLE
   * listing at or past this instant is expired — the public reads treat
   * it as gone immediately and the nightly job flips its stored status
   * to {@code EXPIRED}. Set at creation to {@code now + app.listings.ttl}
   * (default 30 days) by the service layer and reset by renew. Nullable
   * in the schema only so rows predating V41 can be backfilled by the
   * vendor migrations (V42); every row written by the application
   * carries a value, and a null is read as "no deadline" defensively.
   */
  @Column(name = "expires_at")
  private Instant expiresAt;

  /**
   * Detail-read view counter (Flyway V46, backlog #186). Never written
   * through this entity: the detail read bumps it with one atomic
   * {@code UPDATE … SET view_count = view_count + 1} statement
   * ({@link ItemRepository#incrementViewCount}) so concurrent readers
   * cannot lose increments to a {@code @Version} race. This field only
   * carries the persisted value into read mappings.
   */
  @Column(name = "view_count", nullable = false)
  private long viewCount;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Item() {}

  public Item(String title, String description, Long priceCents, Long sellerId) {
    this.title = title;
    this.description = description;
    this.priceCents = priceCents;
    this.sellerId = sellerId;
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
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public Long getPriceCents() { return priceCents; }
  public void setPriceCents(Long priceCents) { this.priceCents = priceCents; }
  public ItemStatus getStatus() { return status; }
  public void setStatus(ItemStatus status) { this.status = status; }
  public Long getSellerId() { return sellerId; }
  public Category getCategory() { return category; }
  public void setCategory(Category category) { this.category = category; }
  public Long getVersion() { return version; }
  public String getPhotoUrl() { return photoUrl; }
  public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }
  public List<ItemPhoto> getPhotos() { return photos; }
  public Instant getExpiresAt() { return expiresAt; }
  public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
  public long getViewCount() { return viewCount; }

  /**
   * True when this listing's lifetime has run out at {@code now}: only an
   * AVAILABLE listing can be expired — a RESERVED listing is mid-order
   * (its own lifecycle, #51, governs it) and terminal states are final.
   * The single definition both read paths (lazy-on-read) and the nightly
   * flip job use, so the two can never disagree on the same row.
   */
  public boolean isExpiredAt(Instant now) {
    return status == ItemStatus.AVAILABLE && expiresAt != null && !expiresAt.isAfter(now);
  }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
