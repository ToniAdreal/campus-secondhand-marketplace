package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Step-up proof payload (backlog #188, shared by #218): the current
 * password, plus a second-factor {@code code} (current TOTP code or an
 * unused recovery code) when the account has 2FA enabled. The caller is
 * identified by their Bearer token. Used by {@code POST
 * /api/users/me/export/confirm} and by the general {@code POST
 * /api/auth/step-up} re-proof endpoint — one shape for the one proof
 * discipline, so the two can never drift.
 *
 * <p>The code is deliberately NOT format-validated here, mirroring
 * {@link TotpDisableRequest}: a wrong-shaped value is simply a wrong
 * code and gets the identical 401 as any other failed proof, so the
 * response never reveals which form was expected. For an account
 * without 2FA the field is ignored.
 */
public record DataExportConfirmRequest(
    @NotBlank(message = "current password is required")
    String currentPassword,

    String code) {}
