package com.toni.marketplace.auth;

import java.time.Instant;

/**
 * One enrolled passkey as the settings surface lists it (backlog #220):
 * the row id (for {@code DELETE}), the credential id (base64url — the
 * handle the browser shows is derived from it), the enrolment instant
 * and the last verified signature counter. The public key itself is
 * never exposed — it is verification material, not display data.
 */
public record PasskeyInfo(long id, String credentialId, Instant createdAt, long signCount) {

  public static PasskeyInfo of(PasskeyCredential credential) {
    return new PasskeyInfo(credential.getId(), credential.getCredentialId(),
        credential.getCreatedAt(), credential.getSignCount());
  }
}
