package com.toni.marketplace.item;

import com.toni.marketplace.common.ApiResponse;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's saved listings (backlog #143). Every route is scoped to
 * the JWT principal — there is no user-id parameter anywhere, so one
 * user's saves can never be read or mutated by another. Authentication
 * comes from the security chain's {@code anyRequest().authenticated()}
 * rule: anonymous callers get 401 in the envelope.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "favorites", description = "Saved listings")
@RequestMapping("/api/users/me/favorites")
public class FavoriteController {

  private final FavoriteService favoriteService;

  public FavoriteController(FavoriteService favoriteService) {
    this.favoriteService = favoriteService;
  }

  /** Saves a listing for the caller. Idempotent: re-saving is a no-op 200. */
  @PutMapping("/{itemId}")
  public ApiResponse<Void> save(@PathVariable Long itemId) {
    favoriteService.saveFavorite(currentUserId(), itemId);
    return ApiResponse.ok(null);
  }

  /** Removes the caller's save. Idempotent: unsaving an unsaved listing is a no-op 200. */
  @DeleteMapping("/{itemId}")
  public ApiResponse<Void> unsave(@PathVariable Long itemId) {
    favoriteService.unsaveFavorite(currentUserId(), itemId);
    return ApiResponse.ok(null);
  }

  /** The caller's saved listings, newest save first, in the list-view projection. */
  @GetMapping
  public ApiResponse<List<ItemDto>> list() {
    return ApiResponse.ok(favoriteService.listFavorites(currentUserId()));
  }

  private static Long currentUserId() {
    return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }
}
