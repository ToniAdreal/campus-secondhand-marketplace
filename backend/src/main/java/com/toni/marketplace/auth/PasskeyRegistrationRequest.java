package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Finishes a registration ceremony (backlog #220): the raw {@code
 * PublicKeyCredential} JSON the browser produced with
 * {@code navigator.credentials.create()} — {@code id}, {@code rawId},
 * {@code type} and {@code response.clientDataJSON /
 * attestationObject}, byte fields base64url-encoded exactly as the
 * WebAuthn spec defines. The JSON is verified against the stored
 * single-use registration challenge, never trusted as-is.
 *
 * <p>Backlog #241 adds the optional friendly name: the display-only
 * label for the new row ("My phone"). It is optional and may be
 * blank — a blank or omitted name stores NULL, and the settings
 * surface renders the row with the enrolment-date label instead, so
 * an unnamed passkey is never a blank row. At most 100 characters,
 * matching the Flyway V71 column.
 */
public record PasskeyRegistrationRequest(
    @NotBlank(message = "credential is required")
    String credential,

    @Size(max = 100, message = "name must be at most 100 characters")
    String name) {}
