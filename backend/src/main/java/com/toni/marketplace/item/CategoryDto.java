package com.toni.marketplace.item;

/**
 * Public read model for the listing category taxonomy. Exposes only the
 * fields a UI dropdown needs — never the entity — so the taxonomy can grow
 * (descriptions, icons) without breaking the frontend contract.
 */
public record CategoryDto(Long id, String name, String slug) {

  public static CategoryDto from(Category category) {
    return new CategoryDto(category.getId(), category.getName(), category.getSlug());
  }
}
