package com.toni.marketplace.auth;

/** Application roles. RBAC enforcement (@PreAuthorize) arrives in a later step. */
public enum Role {
  USER,
  ADMIN
}
