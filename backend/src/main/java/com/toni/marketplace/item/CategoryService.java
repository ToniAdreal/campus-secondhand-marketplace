package com.toni.marketplace.item;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Read model for the listing category taxonomy (seeded by Flyway V3).
 *
 * <p>Exists so controllers never touch {@link CategoryRepository} directly —
 * the layering rule enforced by ArchitectureTest. ADMIN write operations
 * (backlog #70) will land here too.
 */
@Service
class CategoryService {

  private final CategoryRepository categories;

  CategoryService(CategoryRepository categories) {
    this.categories = categories;
  }

  List<CategoryDto> listAll() {
    return categories.findAllByOrderByIdAsc().stream().map(CategoryDto::from).toList();
  }
}
