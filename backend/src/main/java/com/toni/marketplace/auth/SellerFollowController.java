package com.toni.marketplace.auth;

import com.toni.marketplace.common.ApiResponse;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Following a seller (backlog #217). Every route is scoped to the JWT
 * principal — the follower is never a request field, so one user's
 * follows can never be read or mutated by another. Authentication comes
 * from the security chain's {@code anyRequest().authenticated()} rule
 * (there is no permitAll matcher for {@code /api/users/**}): anonymous
 * callers get 401 in the envelope, pinned by the anonymous sweep.
 *
 * <p>The follow is a notification tool, not a social graph: the GET
 * list returns the caller's own followed sellers (id + username +
 * followed-at) and follower counts are never exposed anywhere.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "users", description = "Follow a seller")
@RequestMapping("/api/users")
public class SellerFollowController {

  private final SellerFollowService followService;

  public SellerFollowController(SellerFollowService followService) {
    this.followService = followService;
  }

  /** Follows a seller. Idempotent: re-following is a no-op 200. */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "followed (or already followed) (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown user id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
          description = "the caller has blocked this seller (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "following yourself (envelope)")})
  @PostMapping("/me/follows/{sellerId}")
  public ApiResponse<Void> follow(@PathVariable Long sellerId) {
    followService.follow(currentUserId(), sellerId);
    return ApiResponse.ok(null);
  }

  /** Stops following a seller. Idempotent: unfollowing a non-followed seller is a no-op 200. */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "unfollowed (or was not followed) (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown user id (envelope)")})
  @DeleteMapping("/me/follows/{sellerId}")
  public ApiResponse<Void> unfollow(@PathVariable Long sellerId) {
    followService.unfollow(currentUserId(), sellerId);
    return ApiResponse.ok(null);
  }

  /** The caller's following list, newest follow first. */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "the caller's followed sellers (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)")})
  @GetMapping("/me/follows")
  public ApiResponse<List<FollowedSellerDto>> listFollows() {
    return ApiResponse.ok(followService.listFollows(currentUserId()));
  }

  private static Long currentUserId() {
    return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }
}
