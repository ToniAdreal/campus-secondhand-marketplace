package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Password-change payload. The caller is identified by their Bearer access
 * token; the current password proves possession, and the new password must
 * satisfy the same strength policy as registration.
 */
public record PasswordChangeRequest(
    @NotBlank(message = "current password is required")
    String currentPassword,

    @NotBlank(message = "new password is required")
    @Size(min = 8, max = 72, message = "password must be 8-72 characters")
    String newPassword) {}
