package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Account-closure payload (backlog #158). The caller is identified by their
 * Bearer token; the current password proves possession — the same proof
 * discipline as the password change — and, when the account has TOTP 2FA
 * enabled, {@code code} must carry a valid TOTP or recovery code.
 *
 * <p>The code is deliberately NOT format- or presence-validated here: it is
 * required only for 2FA-enabled accounts, and {@code AuthService} folds a
 * missing, wrong-shaped or wrong code into the identical 401 it returns for
 * a wrong password, so the response never reveals which proof failed or
 * which form was expected (mirroring {@link TotpDisableRequest}).
 */
public record AccountClosureRequest(
    @NotBlank(message = "current password is required")
    String currentPassword,

    String code) {}
