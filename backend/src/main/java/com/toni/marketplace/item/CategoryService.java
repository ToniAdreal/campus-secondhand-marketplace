package com.toni.marketplace.item;

import com.toni.marketplace.common.CategoryInUseException;
import com.toni.marketplace.common.DuplicateCategoryException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read model + ADMIN write model for the listing category taxonomy
 * (seeded by Flyway V3).
 *
 * <p>Exists so controllers never touch {@link CategoryRepository} directly —
 * the layering rule enforced by ArchitectureTest. ADMIN write operations
 * (backlog #70) land here too.
 */
@Service
class CategoryService {

  private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

  private final CategoryRepository categories;
  private final ItemRepository items;

  CategoryService(CategoryRepository categories, ItemRepository items) {
    this.categories = categories;
    this.items = items;
  }

  /**
   * Lists the whole taxonomy in seed order. Cached (backlog #87): the
   * browse page and the create-listing form read this on every render,
   * while writes are rare and ADMIN-only. The returned list is
   * unmodifiable ({@code Stream.toList()}), so sharing the cached
   * instance with every caller is safe. Eviction lives on the two write
   * methods below — a write that fails (duplicate, in-use) throws before
   * evicting, so a rejected ADMIN call never flushes a warm cache.
   */
  @Cacheable(cacheNames = CategoryCacheConfig.CATEGORIES_CACHE, key = "'all'")
  public List<CategoryDto> listAll() {
    return categories.findAllByOrderByIdAsc().stream().map(CategoryDto::from).toList();
  }

  /**
   * Creates a category. Name uniqueness is case-insensitive: "gadgets"
   * collides with an existing "Gadgets" (409). The display name keeps its
   * case; the canonical lowercase form carries the uniqueness contract
   * (see {@link Category#canonical}, #47's rule).
   */
  @Transactional
  @CacheEvict(cacheNames = CategoryCacheConfig.CATEGORIES_CACHE, allEntries = true)
  public CategoryDto createCategory(String name, String slug) {
    String displayName = name.trim();
    if (categories.existsByNameLower(Category.canonical(displayName))) {
      throw new DuplicateCategoryException("category name already exists");
    }
    String resolvedSlug = (slug == null || slug.isBlank())
        ? slugify(displayName)
        : slug.trim().toLowerCase(Locale.ROOT);
    if (!SLUG.matcher(resolvedSlug).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "slug must be lowercase letters, digits and single hyphens");
    }
    if (categories.existsBySlug(resolvedSlug)) {
      throw new DuplicateCategoryException("category slug already exists");
    }
    return CategoryDto.from(categories.save(new Category(displayName, resolvedSlug)));
  }

  /**
   * Deletes a category. 404 on unknown id; 409 when listings still
   * reference it — a category delete must never orphan a listing's
   * category (the item.category FK is ON DELETE SET NULL, so this is a
   * product rule enforced here, not a DB cascade).
   */
  @Transactional
  @CacheEvict(cacheNames = CategoryCacheConfig.CATEGORIES_CACHE, allEntries = true)
  public void deleteCategory(Long id) {
    Category category = categories.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "category not found"));
    if (items.existsByCategoryId(id)) {
      throw new CategoryInUseException("category is used by listings");
    }
    categories.delete(category);
  }

  /**
   * Derives a slug from a display name: lowercase, non-alphanumerics
   * become single hyphens, leading/trailing hyphens trimmed. A name with
   * no slugable characters falls back to "category" (the slug-uniqueness
   * check still applies, so the caller may need to pass an explicit slug).
   */
  static String slugify(String name) {
    String slug = name.toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("^-+|-+$", "");
    return slug.isEmpty() ? "category" : slug;
  }
}
