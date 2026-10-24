package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Finishes a registration ceremony (backlog #220): the raw {@code
 * PublicKeyCredential} JSON the browser produced with
 * {@code navigator.credentials.create()} — {@code id}, {@code rawId},
 * {@code type} and {@code response.clientDataJSON /
 * attestationObject}, byte fields base64url-encoded exactly as the
 * WebAuthn spec defines. The JSON is verified against the stored
 * single-use registration challenge, never trusted as-is.
 */
public record PasskeyRegistrationRequest(
    @NotBlank(message = "credential is required")
    String credential) {}
