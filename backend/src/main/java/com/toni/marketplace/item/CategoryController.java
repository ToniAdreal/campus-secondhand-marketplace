package com.toni.marketplace.item;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

  /**
   * Lists the taxonomy with a strong ETag / conditional GET (backlog
   * #145). The validator is the SHA-256 of the serialized DTO list — the
   * same payload {@link CategoryService#listAll()} already serves from
   * the #87 cache — so any ADMIN category write (create / rename /
   * delete, all {@code @CacheEvict}) changes the payload and therefore
   * the ETag, with no extra invalidation bookkeeping. A request whose
   * {@code If-None-Match} carries the current validator (weak comparison,
   * per RFC 9110 §13.1.2 for this header, {@code *} included) answers
   * 304 with an empty body and the ETag repeated; any other validator
   * answers the full 200 envelope.
   *
   * <p>Scope is deliberately categories only: this is a tiny,
   * rarely-changing taxonomy read on every SPA load. Item lists are
   * per-user filtered and change constantly, so they stay uncached and
   * carry no validator.
   */
  @GetMapping
  public ResponseEntity<ApiResponse<List<CategoryDto>>> list(
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
          String ifNoneMatch) {
    List<CategoryDto> payload = categories.listAll();
    String etag = etagFor(payload);
    if (matchesIfNoneMatch(ifNoneMatch, etag)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
    }
    return ResponseEntity.ok().eTag(etag).body(ApiResponse.ok(payload));
  }

  /**
   * Strong validator for a category payload: hex SHA-256 over the DTO
   * list's canonical string form (record {@code toString} is field-order
   * stable, and the list is in seed/id order), so identical payloads
   * always hash identically and any field change rehashes.
   */
  static String etagFor(List<CategoryDto> payload) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      byte[] digest = sha256.digest(payload.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed on every Java platform; this never happens.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /**
   * Whether an {@code If-None-Match} header value matches the current
   * validator: the header may be a comma-separated list, entries may be
   * weak ({@code W/} prefix — stripped, because this header compares
   * weakly) or quoted, and {@code *} matches any current representation.
   */
  static boolean matchesIfNoneMatch(String ifNoneMatch, String etag) {
    if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
      return false;
    }
    for (String candidate : ifNoneMatch.split(",")) {
      String value = candidate.trim();
      if (value.equals("*")) {
        return true;
      }
      if (value.startsWith("W/")) {
        value = value.substring(2).trim();
      }
      if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
        value = value.substring(1, value.length() - 1);
      }
      if (value.equals(etag)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Creates a category. Name uniqueness is case-insensitive; the slug is
   * derived from the name when omitted. Answers 201 with the created row.
   * The acting ADMIN's id (the JWT principal) is threaded to the service
   * so the write's audit row (backlog #132) names its actor.
   */
  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<ApiResponse<CategoryDto>> create(
      @AuthenticationPrincipal Long actorId,
      @Valid @RequestBody CategoryCreateRequest request) {
    CategoryDto created = categories.createCategory(actorId, request.name(), request.slug());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
  }

  /**
   * Renames a category (backlog #132). Name uniqueness is
   * case-insensitive (excluding the category itself); an omitted slug
   * keeps the current slug. Answers 200 with the renamed row; 404 on
   * unknown id, 409 on a colliding name/slug.
   */
  @PatchMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<CategoryDto> update(
      @AuthenticationPrincipal Long actorId, @PathVariable Long id,
      @Valid @RequestBody CategoryUpdateRequest request) {
    return ApiResponse.ok(
        categories.updateCategory(actorId, id, request.name(), request.slug()));
  }

  /**
   * Deletes a category with no listings pointing at it (204). 404 on
   * unknown id; 409 when listings still reference it — use the item edit
   * flow to recategorize those listings first.
   */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal Long actorId, @PathVariable Long id) {
    categories.deleteCategory(actorId, id);
    return ResponseEntity.noContent().build();
  }
}
