package com.toni.marketplace.auth;

import java.time.Instant;
import java.util.List;

/**
 * ADMIN user-management view (backlog #88, extended by #105): the account
 * state after a disable/enable call, and one row of the ADMIN user
 * list/search. Built from the entity deliberately instead of serializing
 * {@link User} — the password hash must never reach the wire. Roles are
 * role names sorted alphabetically for a stable wire shape.
 *
 * <p>Backlog #209 adds {@code totpEnabled} — a bare boolean, never the
 * secret — so the admin users page can offer the TOTP-reset action only
 * on rows that actually have a second factor to reset (the endpoint
 * itself 422s on a non-TOTP account regardless).
 */
public record AdminUserDto(long id, String username, String email, List<String> roles,
                           boolean disabled, boolean totpEnabled, Instant createdAt) {

  public static AdminUserDto of(User user) {
    List<String> roleNames = user.getRoles().stream().map(Role::name).sorted().toList();
    return new AdminUserDto(user.getId(), user.getUsername(), user.getEmail(), roleNames,
        user.isDisabled(), user.isTotpEnabled(), user.getCreatedAt());
  }
}
