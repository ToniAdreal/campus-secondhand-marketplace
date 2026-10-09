package com.toni.marketplace.auth;

/**
 * ADMIN user-management view (backlog #88): the account state after a
 * disable/enable call. Built from the entity deliberately instead of
 * serializing {@link User} — the password hash must never reach the wire.
 */
public record AdminUserDto(long id, String username, boolean disabled) {

  public static AdminUserDto of(User user) {
    return new AdminUserDto(user.getId(), user.getUsername(), user.isDisabled());
  }
}
