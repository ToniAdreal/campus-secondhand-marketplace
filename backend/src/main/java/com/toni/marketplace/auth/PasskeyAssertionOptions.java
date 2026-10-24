package com.toni.marketplace.auth;

import java.util.List;

/**
 * The assertion ceremony options (backlog #220): everything
 * {@code navigator.credentials.get({publicKey: ...})} needs.
 * {@code allowCredentials} lists the account's enrolled credential ids
 * so the browser offers exactly this account's passkeys;
 * {@code userVerification} is {@code "preferred"} — verified users get
 * the stronger ceremony, authenticators without UV still work (the
 * server requires user presence either way).
 */
public record PasskeyAssertionOptions(
    String challenge,
    String rpId,
    List<String> allowCredentials,
    long timeout,
    String userVerification) {}
