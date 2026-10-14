package com.toni.marketplace.item;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's saved searches (backlog #164). Every route is scoped to
 * the JWT principal — there is no user-id parameter anywhere, so one
 * user's saved searches can never be read or mutated by another.
 * Authentication comes from the security chain's
 * {@code anyRequest().authenticated()} rule: anonymous callers get 401
 * in the envelope.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "saved-searches", description = "Saved searches")
@RequestMapping("/api/users/me/saved-searches")
public class SavedSearchController {

  private final SavedSearchService savedSearchService;

  public SavedSearchController(SavedSearchService savedSearchService) {
    this.savedSearchService = savedSearchService;
  }

  /**
   * Saves the caller's current browse filter set. Validation reuses the
   * browse endpoint's own rules (see {@link SavedSearchService}): a
   * negative bound, min &gt; max, or a sort outside the allowlist is
   * 400. Beyond the per-user cap of 20 the answer is 422.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "saved search created (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
          description = "invalid filter set — negative bound, min above max, or unknown sort (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "saved-search cap of 20 per user reached (envelope)")})
  @PostMapping
  public ApiResponse<SavedSearchDto> create(@Valid @RequestBody SavedSearchCreateRequest request) {
    return ApiResponse.ok(savedSearchService.createSavedSearch(currentUserId(), request));
  }

  /** The caller's saved searches, newest first, each with its live match count. */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "the caller's saved searches (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)")})
  @GetMapping
  public ApiResponse<List<SavedSearchDto>> list() {
    return ApiResponse.ok(savedSearchService.listSavedSearches(currentUserId()));
  }

  /**
   * Deletes one of the caller's saved searches. Someone else's id is
   * 404, exactly like an unknown id — no existence leak.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "saved search deleted (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown id, or a saved search owned by another user (envelope)")})
  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    savedSearchService.deleteSavedSearch(currentUserId(), id);
    return ApiResponse.ok(null);
  }

  private static Long currentUserId() {
    return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }
}
