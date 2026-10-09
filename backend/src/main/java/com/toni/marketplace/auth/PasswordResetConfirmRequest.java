package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Password-reset confirmation payload (backlog #90): the raw single-use
 * token delivered through the mail seam plus the new password, which must
 * pass the same strength policy as register / password change.
 */
public record PasswordResetConfirmRequest(
    @NotBlank(message = "token is required")
    String token,

    @NotBlank(message = "new password is required")
    String newPassword) {}
