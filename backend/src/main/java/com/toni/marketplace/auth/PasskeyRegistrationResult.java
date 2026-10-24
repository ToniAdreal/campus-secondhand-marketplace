package com.toni.marketplace.auth;

/**
 * The answer to {@code POST /api/auth/2fa/passkeys/registration/verify}
 * (backlog #220): the enrolled credential id (base64url) — the handle
 * the settings surface lists and {@code DELETE} addresses. The public
 * key itself is never returned.
 */
public record PasskeyRegistrationResult(String credentialId) {}
