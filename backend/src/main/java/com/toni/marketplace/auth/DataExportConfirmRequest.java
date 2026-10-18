package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Step-up confirmation payload for the account data export (backlog
 * #188): {@code POST /api/users/me/export/confirm}. The caller is
 * identified by their Bearer token; minting an export grant demands
 * the fresh proof every other sensitive self-service action already
 * requires — the current password, plus a second-factor {@code code}
 * (current TOTP code or an unused recovery code) when the account has
 * 2FA enabled.
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
