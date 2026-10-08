package com.toni.marketplace.item;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
  Optional<Category> findBySlug(String slug);

  /** Case-insensitive name lookup: the canonical uniqueness key (#70). */
  boolean existsByNameLower(String nameLower);

  /** Slugs are always stored lowercase, so this check is exact. */
  boolean existsBySlug(String slug);

  /** Stable dropdown order: the seed insertion order (taxonomy position). */
  List<Category> findAllByOrderByIdAsc();
}
