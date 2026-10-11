package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;

/** Email-verification confirm payload: the token from the mailed link. */
public record EmailVerificationConfirmRequest(
    @NotBlank(message = "token is required")
    String token) {}
