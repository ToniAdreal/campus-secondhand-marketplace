package com.toni.marketplace.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Email-change payload (backlog #150). The caller is identified by their
 * Bearer token; the current password proves possession — the same proof
 * discipline as the password change — and the new address is validated
 * with the same constraints as registration.
 */
public record EmailChangeRequest(
    @NotBlank(message = "current password is required")
    String currentPassword,

    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid address")
    @Size(max = 255, message = "email is too long")
    String newEmail) {}
