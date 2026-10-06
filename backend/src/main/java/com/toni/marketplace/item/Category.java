package com.toni.marketplace.item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Listing category taxonomy (Electronics, Books &amp; Study, …). Seeded by
 * Flyway V3. A listing's category is optional — older rows are simply
 * uncategorized — so the association is nullable on the item side.
 */
@Entity
@Table(name = "category")
public class Category {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 60)
  private String name;

  @Column(nullable = false, unique = true, length = 60)
  private String slug;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  protected Category() {}

  public Category(String name, String slug) {
    this.name = name;
    this.slug = slug;
  }

  public Long getId() { return id; }
  public String getName() { return name; }
  public String getSlug() { return slug; }
  public Instant getCreatedAt() { return createdAt; }
}
