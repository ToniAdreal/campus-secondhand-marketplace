package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Recovery-code regeneration payload (backlog #127). The caller is
 * identified by their Bearer access token; replacing the one-time
 * recovery-code set demands proof of both knowledge factors the account
 * has: the current password AND a second factor — either the current
 * 6-digit TOTP code from the authenticator app or one of the currently
 * unused recovery codes (a code used here is consumed — the whole old
 * set is deleted by the regeneration anyway). Mirrors
 * {@link TotpDisableRequest}: regeneration and disable share the exact
 * same proof seam in {@code AuthService}, so their payloads match too.
 *
 * <p>The code is deliberately NOT format-validated here, mirroring
 * {@link TotpAuthenticateRequest}: a wrong-shaped value is simply a
 * wrong code and gets the identical 401 as any other failed proof, so
 * the response never reveals which form was expected.
 */
public record TotpRecoveryCodeRegenerateRequest(
    @NotBlank(message = "current password is required")
    String currentPassword,

    @NotBlank(message = "code is required")
    String code) {}
