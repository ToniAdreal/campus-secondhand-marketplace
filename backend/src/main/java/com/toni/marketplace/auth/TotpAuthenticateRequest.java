package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * The second-factor exchange: the signed challenge returned by
 * {@code POST /api/auth/login} (HTTP 202) for a second-factor account,
 * plus exactly one proof — either the current 6-digit TOTP code from
 * the user's authenticator app or one of the one-time recovery codes
 * issued at enable time (backlog #91 — consumed by a successful
 * exchange), or a WebAuthn passkey assertion (backlog #220). On success
 * the response is the normal token pair (with the httpOnly refresh
 * cookie).
 *
 * <p>The code is deliberately NOT format-validated here: a wrong-shaped
 * value is simply a wrong code and gets the identical 401 as any other
 * failed second factor, so the response never reveals which form was
 * expected. (The enable endpoint keeps its strict 6-digit pattern —
 * only a real TOTP code may enroll.) Since #220 the code is also not
 * presence-validated: a passkey assertion travels in {@code assertion}
 * instead, and a missing/blank code on the TOTP path is simply a
 * failed proof — the identical 401.
 *
 * <p>{@code rememberDevice} (backlog #199) is the trusted-device
 * opt-in: when true, a SUCCESSFUL exchange also trusts this device
 * (the response sets the {@code trusted_device} cookie), so later
 * password logins from it skip only this step. Absent/null is false —
 * every pre-#199 client keeps paying the challenge. The flag is
 * honoured only on success; a failed exchange creates no trust.
 */
public record TotpAuthenticateRequest(
    @NotBlank(message = "challenge is required")
    String challenge,

    String code,

    /**
     * The passkey proof (backlog #220): the raw
     * {@code PublicKeyCredential} JSON from
     * {@code navigator.credentials.get()}, verified against the
     * single-use assertion challenge. When present and non-blank the
     * passkey path is taken and {@code code} is ignored; when absent
     * the TOTP/recovery-code path applies. Deliberately not
     * format-validated — a malformed assertion is simply a failed
     * proof, the identical 401.
     */
    String assertion,

    Boolean rememberDevice) {

  /** Two-arg form for pre-#199 call sites: never trust the device. */
  public TotpAuthenticateRequest(String challenge, String code) {
    this(challenge, code, null, null);
  }

  /** Three-arg form for pre-#220 call sites: the TOTP/recovery-code path. */
  public TotpAuthenticateRequest(String challenge, String code, Boolean rememberDevice) {
    this(challenge, code, null, rememberDevice);
  }

  /** The opt-in as a plain boolean (absent/null reads as false). */
  public boolean rememberDeviceOrFalse() {
    return Boolean.TRUE.equals(rememberDevice);
  }
}
