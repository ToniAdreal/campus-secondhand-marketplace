package com.toni.marketplace.item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;

/**
 * Listing category taxonomy (Electronics, Books &amp; Study, …). Seeded by
 * Flyway V3. A listing's category is optional — older rows are simply
 * uncategorized — so the association is nullable on the item side.
 *
 * <p>{@code nameLower} is the canonical lowercase form of {@code name}
 * (backlog #70, Flyway V16): the uniqueness contract is case-insensitive,
 * and the two supported databases disagree on case sensitivity (MySQL's
 * default collation is case-insensitive, H2 is not), so the canonical form
 * is stored explicitly and carries the unique constraint instead of
 * relying on collation behavior. {@code name} itself keeps display case.
 */
@Entity
@Table(name = "category")
public class Category {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 60)
  private String name;

  @Column(name = "name_lower", nullable = false, length = 60)
  private String nameLower;

  @Column(nullable = false, unique = true, length = 60)
  private String slug;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  protected Category() {}

  public Category(String name, String slug) {
    this.name = name;
    this.slug = slug;
    this.nameLower = canonical(name);
  }

  /** Canonical uniqueness key: lowercase (ROOT locale), per #47's rule. */
  public static String canonical(String name) {
    return name == null ? null : name.toLowerCase(Locale.ROOT);
  }

  public Long getId() { return id; }
  public String getName() { return name; }
  public String getNameLower() { return nameLower; }
  public String getSlug() { return slug; }
  public Instant getCreatedAt() { return createdAt; }
}
