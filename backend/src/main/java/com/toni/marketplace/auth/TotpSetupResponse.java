package com.toni.marketplace.auth;

/**
 * The 2FA setup response: the Base32 shared secret plus the otpauth://
 * provisioning URI the user scans into their authenticator app. Returned
 * once, over the authenticated session that enrolled — the client should
 * show it and forget it; the secret is re-issuable via setup.
 */
public record TotpSetupResponse(String secret, String otpauthUri) {}
