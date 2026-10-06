package com.toni.marketplace.auth;

/** Application roles. RBAC is enforced with {@code @PreAuthorize} method security
 * (enabled in {@link SecurityConfig}); keep the set small and explicit. */
public enum Role {
  USER,
  ADMIN
}
