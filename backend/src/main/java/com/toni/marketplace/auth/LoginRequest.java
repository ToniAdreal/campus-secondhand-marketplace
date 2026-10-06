package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;

/** Login payload; the identifier accepts either username or email. */
public record LoginRequest(
    @NotBlank(message = "username or email is required")
    String usernameOrEmail,

    @NotBlank(message = "password is required")
    String password) {}
