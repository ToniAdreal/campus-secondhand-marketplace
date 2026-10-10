package com.toni.marketplace.auth;

import com.toni.marketplace.common.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN user management (backlog #88; list/search added by #105). Until
 * #88 the ADMIN role could moderate listings and categories but had no way
 * to act on an abusive account; until #105 it could not even find the id
 * to disable without database access. All endpoints are ADMIN-only
 * (class-level {@code @PreAuthorize}); anonymous callers get the JSON 401
 * envelope and non-admin callers the JSON 403 envelope. The disable/enable
 * guard rules (no self-disable, no disabling another ADMIN, unknown id
 * → 404) live in {@link AuthService#disableUser}.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "admin", description = "ADMIN user administration")
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
class AdminUserController {

  private final AuthService auth;

  AdminUserController(AuthService auth) {
    this.auth = auth;
  }

  /**
   * Lists users, newest first, optionally filtered by {@code q} — a
   * case-insensitive substring over username and email (backlog #105).
   * Default page size 20, sorted by {@code createdAt} desc with
   * {@code id} desc as the tie-break; the #75 global cap clamps
   * oversized {@code size} values. Rows are {@link AdminUserDto}
   * projections — never the entity, never a password hash.
   */
  @GetMapping
  public ApiResponse<Page<AdminUserDto>> list(
      @RequestParam(name = "q", required = false) String q,
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    return ApiResponse.ok(auth.listAdminUsers(q, pageable));
  }

  /**
   * Disables an account: login 401s, live Bearer tokens 401, and every
   * refresh-token family is revoked. Answers the account's new state.
   */
  @PostMapping("/{id}/disable")
  public ResponseEntity<ApiResponse<AdminUserDto>> disable(
      @AuthenticationPrincipal Long actorId, @PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.ok(AdminUserDto.of(auth.disableUser(actorId, id))));
  }

  /**
   * Re-enables an account so it can log in again (with fresh tokens —
   * pre-disable tokens stay dead). Answers the account's new state.
   */
  @PostMapping("/{id}/enable")
  public ResponseEntity<ApiResponse<AdminUserDto>> enable(
      @AuthenticationPrincipal Long actorId, @PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.ok(AdminUserDto.of(auth.enableUser(actorId, id))));
  }
}
