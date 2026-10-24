import { describe, expect, it } from 'vitest';
import {
  assertionOptionsToRequestOptions,
  base64UrlDecodeToBytes,
  base64UrlEncode,
  registrationOptionsToCreationOptions,
  serializeAssertionCredential,
  serializeRegistrationCredential,
  webauthnSupported,
  type PasskeyAssertionOptions,
  type PasskeyRegistrationOptions,
} from './webauthn';

describe('base64UrlEncode / base64UrlDecodeToBytes', () => {
  it('round-trips arbitrary bytes without padding', () => {
    const bytes = new Uint8Array([0, 1, 2, 250, 251, 252, 253, 254, 255]);
    expect(base64UrlDecodeToBytes(base64UrlEncode(bytes))).toEqual(bytes);
    expect(base64UrlEncode(bytes)).not.toMatch(/[+/=]/);
  });

  it('decodes the unpadded form the backend emits', () => {
    expect(Array.from(base64UrlDecodeToBytes('aGk-bA'))).toEqual([104, 105, 62, 108]);
  });
});

describe('webauthnSupported', () => {
  it('is true when PublicKeyCredential and navigator.credentials are present', () => {
    (window as unknown as Record<string, unknown>).PublicKeyCredential = function () {};
    Object.defineProperty(window.navigator, 'credentials', {
      value: {},
      configurable: true,
    });
    expect(webauthnSupported()).toBe(true);
    delete (window as unknown as Record<string, unknown>).PublicKeyCredential;
    delete (window.navigator as unknown as Record<string, unknown>).credentials;
  });

  it('is false when PublicKeyCredential is absent', () => {
    delete (window as unknown as Record<string, unknown>).PublicKeyCredential;
    expect(webauthnSupported()).toBe(false);
  });

  it('is false when navigator.credentials is absent', () => {
    (window as unknown as Record<string, unknown>).PublicKeyCredential = function () {};
    delete (window.navigator as unknown as Record<string, unknown>).credentials;
    expect(webauthnSupported()).toBe(false);
    delete (window as unknown as Record<string, unknown>).PublicKeyCredential;
  });
});

const registrationOptions: PasskeyRegistrationOptions = {
  challenge: base64UrlEncode(new Uint8Array([1, 2, 3, 4])),
  rpId: 'localhost',
  rpName: 'CampusMarketplace',
  user: { id: base64UrlEncode(new Uint8Array([0, 0, 0, 0, 0, 0, 0, 7])), name: 'toni', displayName: 'toni' },
  excludeCredentials: [base64UrlEncode(new Uint8Array([9, 9, 9]))],
  timeout: 60000,
  attestation: 'none',
};

describe('registrationOptionsToCreationOptions', () => {
  it('converts the wire encoding to ArrayBuffers for navigator.credentials.create', () => {
    const built = registrationOptionsToCreationOptions(registrationOptions);
    expect(Array.from(built.challenge as Uint8Array)).toEqual([1, 2, 3, 4]);
    expect(built.rp).toMatchObject({ id: 'localhost', name: 'CampusMarketplace' });
    expect(Array.from(built.user.id as Uint8Array)).toEqual([
      0, 0, 0, 0, 0, 0, 0, 7,
    ]);
    expect(built.excludeCredentials).toHaveLength(1);
    expect(built.attestation).toBe('none');
  });
});

const assertionOptions: PasskeyAssertionOptions = {
  challenge: base64UrlEncode(new Uint8Array([5, 6, 7, 8])),
  rpId: 'localhost',
  allowCredentials: [base64UrlEncode(new Uint8Array([9, 9, 9]))],
  timeout: 60000,
  userVerification: 'preferred',
};

describe('assertionOptionsToRequestOptions', () => {
  it('lists exactly the enrolled credential ids for navigator.credentials.get', () => {
    const built = assertionOptionsToRequestOptions(assertionOptions);
    expect(Array.from(built.challenge as Uint8Array)).toEqual([5, 6, 7, 8]);
    expect(built.allowCredentials).toHaveLength(1);
    expect(built.allowCredentials?.[0].type).toBe('public-key');
    expect(built.userVerification).toBe('preferred');
  });
});

function fakeCredential(response: object): PublicKeyCredential {
  const fake = {
    id: 'cred-id',
    rawId: new Uint8Array([9, 9, 9]).buffer,
    type: 'public-key',
    response,
  };
  return fake as unknown as PublicKeyCredential;
}

describe('serializeRegistrationCredential', () => {
  it('emits the webauthn4j-parsable shape with base64url byte fields', () => {
    const json = serializeRegistrationCredential(
      fakeCredential({
        clientDataJSON: new Uint8Array([10, 11]).buffer,
        attestationObject: new Uint8Array([12, 13]).buffer,
      }),
    );
    const parsed = JSON.parse(json);
    expect(parsed.id).toBe('cred-id');
    expect(parsed.type).toBe('public-key');
    // the backend reads the top-level id to select the credential
    expect(typeof parsed.id).toBe('string');
    expect(base64UrlDecodeToBytes(parsed.response.clientDataJSON)).toEqual(
      new Uint8Array([10, 11]),
    );
    expect(base64UrlDecodeToBytes(parsed.response.attestationObject)).toEqual(
      new Uint8Array([12, 13]),
    );
  });
});

describe('serializeAssertionCredential', () => {
  it('emits clientDataJSON + authenticatorData + signature as base64url', () => {
    const json = serializeAssertionCredential(
      fakeCredential({
        clientDataJSON: new Uint8Array([20]).buffer,
        authenticatorData: new Uint8Array([21]).buffer,
        signature: new Uint8Array([22]).buffer,
        userHandle: null,
      }),
    );
    const parsed = JSON.parse(json);
    expect(parsed.response.userHandle).toBeNull();
    expect(base64UrlDecodeToBytes(parsed.response.signature)).toEqual(new Uint8Array([22]));
  });
});
