package com.toni.marketplace.auth;

import jakarta.validation.constraints.Pattern;

/**
 * A 6-digit TOTP code from the user's authenticator app. Malformed codes
 * (anything but exactly six digits) are rejected by validation with 400
 * before any cryptographic check runs.
 */
public record TotpCodeRequest(
    @Pattern(regexp = "\\d{6}", message = "code must be a 6-digit number")
    String code) {}
