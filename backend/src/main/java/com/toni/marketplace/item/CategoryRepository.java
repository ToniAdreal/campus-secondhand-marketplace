package com.toni.marketplace.item;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
  Optional<Category> findBySlug(String slug);

  /** Stable dropdown order: the seed insertion order (taxonomy position). */
  List<Category> findAllByOrderByIdAsc();
}
