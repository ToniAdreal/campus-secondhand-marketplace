package com.toni.marketplace.item;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditService;
import com.toni.marketplace.audit.AuditTargetType;
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
 *
 * <p>Every successful write also appends an audit row (backlog #132)
 * through {@link AuditService} from inside the write's own transaction —
 * comparably privileged transitions (ADMIN disable/enable, order money
 * moves) already did, while these taxonomy writes previously left no
 * trail. A rejected write throws before the record call (and its
 * transaction rolls back regardless), so it leaves no row; the actor is
 * the authenticated ADMIN's id, threaded from the controller's
 * {@code @AuthenticationPrincipal} — never a request parameter.
 */
@Service
class CategoryService {

  private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

  private final CategoryRepository categories;
  private final ItemRepository items;
  private final AuditService audit;

  CategoryService(CategoryRepository categories, ItemRepository items, AuditService audit) {
    this.categories = categories;
    this.items = items;
    this.audit = audit;
  }

  /**
   * Audit accessor with a no-op fallback, mirroring {@code OrderService}:
   * a hand-constructed service without an AuditService must never fail
   * on the trail. Production always gets the Spring bean.
   */
  private AuditService audit() {
    return audit != null ? audit : AuditService.noop();
  }

  /**
   * Lists the whole taxonomy in seed order. Cached (backlog #87): the
   * browse page and the create-listing form read this on every render,
   * while writes are rare and ADMIN-only. The returned list is
   * unmodifiable ({@code Stream.toList()}), so sharing the cached
   * instance with every caller is safe. Eviction lives on the three write
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
  public CategoryDto createCategory(long actorId, String name, String slug) {
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
    Category saved = categories.save(new Category(displayName, resolvedSlug));
    audit().record(actorId, AuditAction.CATEGORY_CREATED, AuditTargetType.CATEGORY,
        saved.getId());
    return CategoryDto.from(saved);
  }

  /**
   * Renames a category (backlog #132 — the update path whose audit action
   * the #132 spec requires; before it, categories could only be created
   * and deleted). 404 on unknown id. Name uniqueness mirrors create but
   * excludes the category itself, so a pure case change ("gadgets" →
   * "Gadgets") is allowed. An omitted/blank slug keeps the current slug;
   * a supplied slug is validated exactly like create's and must not
   * collide with another category. Writes a {@code CATEGORY_UPDATED}
   * audit row in the same transaction; a rejected rename writes none.
   */
  @Transactional
  @CacheEvict(cacheNames = CategoryCacheConfig.CATEGORIES_CACHE, allEntries = true)
  public CategoryDto updateCategory(long actorId, Long id, String name, String slug) {
    Category category = categories.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "category not found"));
    String displayName = name.trim();
    String canonical = Category.canonical(displayName);
    if (!canonical.equals(category.getNameLower())
        && categories.existsByNameLower(canonical)) {
      throw new DuplicateCategoryException("category name already exists");
    }
    String resolvedSlug = category.getSlug();
    if (slug != null && !slug.isBlank()) {
      resolvedSlug = slug.trim().toLowerCase(Locale.ROOT);
      if (!SLUG.matcher(resolvedSlug).matches()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "slug must be lowercase letters, digits and single hyphens");
      }
      if (!resolvedSlug.equals(category.getSlug())
          && categories.existsBySlug(resolvedSlug)) {
        throw new DuplicateCategoryException("category slug already exists");
      }
    }
    category.rename(displayName, resolvedSlug);
    Category saved = categories.save(category);
    audit().record(actorId, AuditAction.CATEGORY_UPDATED, AuditTargetType.CATEGORY,
        saved.getId());
    return CategoryDto.from(saved);
  }

  /**
   * Deletes a category. 404 on unknown id; 409 when listings still
   * reference it — a category delete must never orphan a listing's
   * category (the item.category FK is ON DELETE SET NULL, so this is a
   * product rule enforced here, not a DB cascade).
   */
  @Transactional
  @CacheEvict(cacheNames = CategoryCacheConfig.CATEGORIES_CACHE, allEntries = true)
  public void deleteCategory(long actorId, Long id) {
    Category category = categories.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "category not found"));
    if (items.existsByCategoryId(id)) {
      throw new CategoryInUseException("category is used by listings");
    }
    categories.delete(category);
    // Recorded after the delete, still inside the same transaction: the
    // row names the now-deleted category id, which stays meaningful
    // because the audit table carries no foreign keys (backlog #132).
    audit().record(actorId, AuditAction.CATEGORY_DELETED, AuditTargetType.CATEGORY, id);
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
