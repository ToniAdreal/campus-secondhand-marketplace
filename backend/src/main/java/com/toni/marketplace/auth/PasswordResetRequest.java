package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Password-reset request payload (backlog #90); the identifier accepts
 * either username or email, exactly like login. The endpoint answers the
 * identical 200 envelope whether or not the account exists — this payload
 * is the only input, and nothing about the response varies with it.
 */
public record PasswordResetRequest(
    @NotBlank(message = "username or email is required")
    String usernameOrEmail) {}
