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
 * User-to-user blocking (backlog #187). Every route is scoped to the
 * JWT principal — the blocker is never a request field, so one user's
 * blocks can never be read or mutated by another. Authentication comes
 * from the security chain's {@code anyRequest().authenticated()} rule
 * (there is no permitAll matcher for {@code /api/users/**}): anonymous
 * callers get 401 in the envelope, pinned by the anonymous sweep.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "users", description = "User blocking")
@RequestMapping("/api/users")
public class UserBlockController {

  private final UserBlockService blockService;

  public UserBlockController(UserBlockService blockService) {
    this.blockService = blockService;
  }

  /** Blocks a user. Idempotent: re-blocking is a no-op 200. */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "blocked (or already blocked) (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown user id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "blocking yourself (envelope)")})
  @PostMapping("/{id}/block")
  public ApiResponse<Void> block(@PathVariable Long id) {
    blockService.block(currentUserId(), id);
    return ApiResponse.ok(null);
  }

  /** Removes the caller's block of a user. Idempotent: unblocking a non-blocked user is a no-op 200. */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "unblocked (or was not blocked) (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown user id (envelope)")})
  @DeleteMapping("/{id}/block")
  public ApiResponse<Void> unblock(@PathVariable Long id) {
    blockService.unblock(currentUserId(), id);
    return ApiResponse.ok(null);
  }

  /** The caller's blocked users, newest block first. */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "the caller's blocked users (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)")})
  @GetMapping("/me/blocks")
  public ApiResponse<List<BlockedUserDto>> listBlocks() {
    return ApiResponse.ok(blockService.listBlocks(currentUserId()));
  }

  private static Long currentUserId() {
    return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }
}
