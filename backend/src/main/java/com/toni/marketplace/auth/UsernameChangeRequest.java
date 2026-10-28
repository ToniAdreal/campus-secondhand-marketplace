package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Username-change payload (backlog #256). The format rules mirror
 * {@link RegisterRequest} exactly — registration carries no pattern beyond
 * the length rule, so neither does this: 3–60 characters, stored
 * canonical-lowercase by {@link AuthService#changeUsername}.
 */
public record UsernameChangeRequest(
    @NotBlank(message = "username is required")
    @Size(min = 3, max = 60, message = "username must be 3-60 characters")
    String username) {}
