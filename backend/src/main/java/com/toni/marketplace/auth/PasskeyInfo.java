package com.toni.marketplace.auth;

import java.time.Instant;

/**
 * One enrolled passkey as the settings surface lists it (backlog #220):
 * the row id (for {@code DELETE}), the credential id (base64url — the
 * handle the browser shows is derived from it), the enrolment instant
 * and the last verified signature counter. The public key itself is
 * never exposed — it is verification material, not display data.
 *
 * <p>Backlog #241 adds the friendly name: the display-only label the
 * holder gave the row at enrolment or via rename. It is NULL for rows
 * enrolled before #241 and for rows renamed to blank — the settings
 * surface renders those with the enrolment-date label ("Passkey added
 * &lt;date&gt;"), never a blank row, so a NULL here is a display
 * fallback, not missing data.
 */
public record PasskeyInfo(long id, String credentialId, Instant createdAt, long signCount,
                          String name) {

  public static PasskeyInfo of(PasskeyCredential credential) {
    return new PasskeyInfo(credential.getId(), credential.getCredentialId(),
        credential.getCreatedAt(), credential.getSignCount(), credential.getName());
  }
}
