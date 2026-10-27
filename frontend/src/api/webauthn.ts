/**
 * WebAuthn passkey browser wiring (backlog #220): the pure, framework-free
 * half of the feature. The backend mints ceremony options whose byte
 * fields are base64url strings (the WebAuthn wire encoding); the browser
 * needs ArrayBuffers for `navigator.credentials.create/get`, and the
 * resulting `PublicKeyCredential` comes back with ArrayBuffers that must
 * be serialized to the same base64url JSON shape the backend's
 * webauthn4j converter parses (see the backend PasskeyCeremonyTest,
 * which round-trips exactly this shape through a software
 * authenticator).
 *
 * Every function here is pure — navigator access lives in the pages —
 * so this module is covered by direct Vitest cases.
 */

/** True when the browser can do WebAuthn at all (backlog #220). */
export function webauthnSupported(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.PublicKeyCredential !== 'undefined' &&
    typeof navigator !== 'undefined' &&
    !!navigator.credentials
  );
}

/** base64url (unpadded) encode, the WebAuthn wire encoding for byte fields. */
export function base64UrlEncode(bytes: ArrayBuffer | Uint8Array): string {
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let binary = '';
  for (const b of view) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** base64url decode to bytes. Throws on malformed input (callers surface one message). */
export function base64UrlDecodeToBytes(s: string): Uint8Array {
  const base64 = s.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(base64);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}

/** The registration ceremony options, as the backend's envelope carries them. */
export interface PasskeyRegistrationOptions {
  challenge: string;
  rpId: string;
  rpName: string;
  user: { id: string; name: string; displayName: string };
  excludeCredentials: string[];
  timeout: number;
  attestation: string;
}

/** The assertion ceremony options, as the backend's envelope carries them. */
export interface PasskeyAssertionOptions {
  challenge: string;
  rpId: string;
  allowCredentials: string[];
  timeout: number;
  userVerification: string;
}

/**
 * One enrolled passkey as the settings surface lists it (no public key — never exposed).
 * Backlog #241: `name` is the display-only friendly label; it is null for rows enrolled
 * before #241 and for rows renamed to blank — the settings surface renders those with
 * the enrolment-date label, never a blank row.
 */
export interface PasskeyInfo {
  id: number;
  credentialId: string;
  createdAt: string;
  signCount: number;
  name: string | null;
}

function toDescriptor(id: string): PublicKeyCredentialDescriptor {
  return {
    type: 'public-key',
    id: base64UrlDecodeToBytes(id),
    transports: [],
  };
}

/**
 * Builds the `publicKey` dict for `navigator.credentials.create()` from
 * the server's registration options. The server offers ES256/RS256; the
 * browser picks what the authenticator supports.
 */
export function registrationOptionsToCreationOptions(
  opts: PasskeyRegistrationOptions,
): PublicKeyCredentialCreationOptions {
  return {
    challenge: base64UrlDecodeToBytes(opts.challenge),
    rp: { id: opts.rpId, name: opts.rpName },
    user: {
      id: base64UrlDecodeToBytes(opts.user.id),
      name: opts.user.name,
      displayName: opts.user.displayName,
    },
    pubKeyCredParams: [
      { type: 'public-key', alg: -7 }, // ES256
      { type: 'public-key', alg: -257 }, // RS256
    ],
    timeout: opts.timeout,
    excludeCredentials: opts.excludeCredentials.map(toDescriptor),
    attestation: 'none',
  };
}

/**
 * Builds the `publicKey` dict for `navigator.credentials.get()` from
 * the server's assertion options. `allowCredentials` lists exactly this
 * account's enrolled passkeys, so the browser offers no one else's.
 */
export function assertionOptionsToRequestOptions(
  opts: PasskeyAssertionOptions,
): PublicKeyCredentialRequestOptions {
  return {
    challenge: base64UrlDecodeToBytes(opts.challenge),
    rpId: opts.rpId,
    timeout: opts.timeout,
    allowCredentials: opts.allowCredentials.map(toDescriptor),
    userVerification: 'preferred',
  };
}

function bufferOf(value: ArrayBuffer | null): string | null {
  return value ? base64UrlEncode(value) : null;
}

/**
 * Serializes the `navigator.credentials.create()` result to the
 * PublicKeyCredential JSON the backend verifies — byte fields as
 * base64url strings, exactly the shape webauthn4j's JSON converter
 * parses (attestationObject + clientDataJSON only).
 */
export function serializeRegistrationCredential(credential: PublicKeyCredential): string {
  const response = credential.response as AuthenticatorAttestationResponse;
  return JSON.stringify({
    id: credential.id,
    rawId: base64UrlEncode(credential.rawId),
    type: credential.type,
    response: {
      clientDataJSON: base64UrlEncode(response.clientDataJSON),
      attestationObject: base64UrlEncode(response.attestationObject),
    },
  });
}

/**
 * Serializes the `navigator.credentials.get()` result to the
 * PublicKeyCredential JSON the backend verifies — clientDataJSON +
 * authenticatorData + signature (+ userHandle when the authenticator
 * provides one).
 */
export function serializeAssertionCredential(credential: PublicKeyCredential): string {
  const response = credential.response as AuthenticatorAssertionResponse;
  return JSON.stringify({
    id: credential.id,
    rawId: base64UrlEncode(credential.rawId),
    type: credential.type,
    response: {
      clientDataJSON: base64UrlEncode(response.clientDataJSON),
      authenticatorData: base64UrlEncode(response.authenticatorData),
      signature: base64UrlEncode(response.signature),
      userHandle: bufferOf(response.userHandle),
    },
  });
}
