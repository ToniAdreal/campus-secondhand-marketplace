package com.toni.marketplace.item;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Listing category taxonomy (seeded by Flyway V3).
 *
 * <p>Reading is available to any authenticated user — the only consumer is
 * the create-listing form, which requires a signed-in seller anyway.
 * Writes are ADMIN-only (backlog #70): taxonomy changes reshape every
 * seller's dropdown, so they are not a self-service operation.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "categories", description = "Listing categories")
@RequestMapping("/api/categories")
class CategoryController {

  private final CategoryService categories;

  CategoryController(CategoryService categories) {
    this.categories = categories;
  }

  @GetMapping
  public ApiResponse<List<CategoryDto>> list() {
    return ApiResponse.ok(categories.listAll());
  }

  /**
   * Creates a category. Name uniqueness is case-insensitive; the slug is
   * derived from the name when omitted. Answers 201 with the created row.
   */
  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<ApiResponse<CategoryDto>> create(
      @Valid @RequestBody CategoryCreateRequest request) {
    CategoryDto created = categories.createCategory(request.name(), request.slug());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
  }

  /**
   * Deletes a category with no listings pointing at it (204). 404 on
   * unknown id; 409 when listings still reference it — use the item edit
   * flow to recategorize those listings first.
   */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Void> delete(@PathVariable Long id) {
    categories.deleteCategory(id);
    return ResponseEntity.noContent().build();
  }
}
