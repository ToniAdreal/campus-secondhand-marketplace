package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The second-factor exchange: the signed challenge returned by
 * {@code POST /api/auth/login} (HTTP 202) for a TOTP-enabled account, plus
 * the current 6-digit code from the user's authenticator app. On success
 * the response is the normal token pair (with the httpOnly refresh cookie).
 */
public record TotpAuthenticateRequest(
    @NotBlank(message = "challenge is required")
    String challenge,

    @Pattern(regexp = "\\d{6}", message = "code must be a 6-digit number")
    String code) {}
