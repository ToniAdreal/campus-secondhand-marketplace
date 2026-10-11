package com.toni.marketplace.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Email-verification resend payload. The address is validated for shape
 * only — the endpoint answers the identical 200 envelope for every
 * well-formed address, account or not (no enumeration oracle).
 */
public record EmailVerificationResendRequest(
    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid address")
    @Size(max = 255, message = "email is too long")
    String email) {}
