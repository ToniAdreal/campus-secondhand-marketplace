package com.toni.marketplace.auth;

import java.util.List;

/**
 * The registration ceremony options (backlog #220): everything
 * {@code navigator.credentials.create({publicKey: ...})} needs. The
 * challenge is the base64url of the raw 32 bytes whose SHA-256 is
 * stored single-use server-side; {@code user.id} is the opaque 8-byte
 * big-endian account id (never the username — the handle must not leak
 * identity to the authenticator); {@code excludeCredentials} lists the
 * account's existing credential ids so the authenticator refuses a
 * duplicate enrolment.
 */
public record PasskeyRegistrationOptions(
    String challenge,
    String rpId,
    String rpName,
    PasskeyUserEntity user,
    List<String> excludeCredentials,
    long timeout,
    String attestation) {

  /** The {@code publicKey.user} entity: opaque id plus human labels. */
  public record PasskeyUserEntity(String id, String name, String displayName) {}
}
