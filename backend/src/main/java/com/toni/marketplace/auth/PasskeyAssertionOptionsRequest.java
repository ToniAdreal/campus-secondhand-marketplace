package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Opens an assertion ceremony (backlog #220, public — the caller is not
 * authenticated yet): carries the 202 login challenge token, which
 * resolves to the challenged account exactly like the
 * {@code /2fa/authenticate} exchange does.
 */
public record PasskeyAssertionOptionsRequest(
    @NotBlank(message = "challenge is required")
    String challenge) {}
