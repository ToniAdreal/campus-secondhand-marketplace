package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * TOTP disable payload (backlog #121). The caller is identified by their
 * Bearer access token; turning 2FA off demands proof of both knowledge
 * factors the account has: the current password AND a second factor —
 * either the current 6-digit TOTP code from the authenticator app or one
 * of the one-time recovery codes issued at enable time (backlog #91),
 * which a successful disable consumes (the whole set is deleted with the
 * enrollment anyway).
 *
 * <p>The code is deliberately NOT format-validated here, mirroring
 * {@link TotpAuthenticateRequest}: a wrong-shaped value is simply a wrong
 * code and gets the identical 401 as any other failed proof, so the
 * response never reveals which form was expected.
 */
public record TotpDisableRequest(
    @NotBlank(message = "current password is required")
    String currentPassword,

    @NotBlank(message = "code is required")
    String code) {}
