package com.toni.marketplace.auth;

import java.util.List;

/**
 * The 2FA enable response (backlog #91): the freshly issued one-time
 * recovery codes, in their display form ({@code XXXX-XXXX-XXXX-XXXX}).
 * This is the ONLY time the codes ever appear anywhere — the server
 * stores SHA-256 hashes — so the client must show them once for the
 * user to save, exactly like the setup secret.
 */
public record TotpEnableResponse(List<String> recoveryCodes) {}
