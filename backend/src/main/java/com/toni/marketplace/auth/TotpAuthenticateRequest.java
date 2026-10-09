package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * The second-factor exchange: the signed challenge returned by
 * {@code POST /api/auth/login} (HTTP 202) for a TOTP-enabled account, plus
 * either the current 6-digit TOTP code from the user's authenticator app
 * or one of the one-time recovery codes issued at enable time (backlog
 * #91 — consumed by a successful exchange). On success the response is
 * the normal token pair (with the httpOnly refresh cookie).
 *
 * <p>The code is deliberately NOT format-validated here: a wrong-shaped
 * value is simply a wrong code and gets the identical 401 as any other
 * failed second factor, so the response never reveals which form was
 * expected. (The enable endpoint keeps its strict 6-digit pattern —
 * only a real TOTP code may enroll.)
 */
public record TotpAuthenticateRequest(
    @NotBlank(message = "challenge is required")
    String challenge,

    @NotBlank(message = "code is required")
    String code) {}
