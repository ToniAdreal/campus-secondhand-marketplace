package com.toni.marketplace.item;

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

/**
 * A second-hand listing. Seller linkage is a plain sellerId in this scaffold;
 * the User entity and auth arrive in a later step.
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
  @Column(nullable = false, length = 16)
  private ItemStatus status = ItemStatus.AVAILABLE;

  @Column(name = "seller_id", nullable = false)
  private Long sellerId;

  /** Optimistic locking: concurrent buyers racing on one item fail fast. */
  @Version
  private Long version;

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
  public Long getVersion() { return version; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
