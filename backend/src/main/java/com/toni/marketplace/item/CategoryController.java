package com.toni.marketplace.item;

import com.toni.marketplace.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the listing category taxonomy (seeded by Flyway V3).
 * Kept behind authentication for now — the only consumer is the
 * create-listing form, which requires a signed-in seller anyway.
 */
@RestController
@RequestMapping("/api/categories")
class CategoryController {

  private final CategoryRepository categories;

  CategoryController(CategoryRepository categories) {
    this.categories = categories;
  }

  @GetMapping
  public ApiResponse<List<CategoryDto>> list() {
    List<CategoryDto> result = categories.findAllByOrderByIdAsc().stream()
        .map(CategoryDto::from)
        .toList();
    return ApiResponse.ok(result);
  }
}
