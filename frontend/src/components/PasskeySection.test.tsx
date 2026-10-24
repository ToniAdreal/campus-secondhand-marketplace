import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { api } from '../api/client';
import PasskeySection from './PasskeySection';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');
const deleteSpy = vi.spyOn(api, 'delete');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function axiosFailure(status: number, code: number, message: string) {
  return {
    isAxiosError: true,
    response: { status, data: { code, message, data: null } },
  };
}

const registrationOptions = {
  challenge: 'Y2hhbGxlbmdl',
  rpId: 'localhost',
  rpName: 'CampusMarketplace',
  user: { id: 'AAAAAAAAAAc', name: 'toni', displayName: 'toni' },
  excludeCredentials: [],
  timeout: 60000,
  attestation: 'none',
};

function mockWebAuthn() {
  const create = vi.fn().mockResolvedValue({
    id: 'new-cred-id',
    rawId: new Uint8Array([7]).buffer,
    type: 'public-key',
    response: {
      clientDataJSON: new Uint8Array([1]).buffer,
      attestationObject: new Uint8Array([2]).buffer,
    },
  });
  Object.defineProperty(window.navigator, 'credentials', {
    value: { create, get: vi.fn() },
    configurable: true,
  });
  return create;
}

describe('PasskeySection (backlog #220)', () => {
  beforeEach(() => {
    mockWebAuthn();
  });

  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
  });

  it('lists the enrolled passkeys on mount, without public keys', async () => {
    getSpy.mockResolvedValueOnce(
      envelope([
        { id: 11, credentialId: 'cred-aaa', createdAt: '2026-10-20T01:02:03Z', signCount: 0 },
      ]),
    );
    render(<PasskeySection totpOn={false} />);

    expect(await screen.findByText(/Passkeys/)).toBeTruthy();
    expect(getSpy).toHaveBeenCalledWith('/auth/2fa/passkeys');
    // the row shows the enrolment, not the key material
    expect(screen.getByText(/Passkey added/)).toBeTruthy();
    expect(screen.queryByText(/cred-aaa/)).toBeNull();
  });

  it('renders an empty state with no rows when none are enrolled', async () => {
    getSpy.mockResolvedValueOnce(envelope([]));
    render(<PasskeySection totpOn={false} />);

    await waitFor(() => expect(getSpy).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: /remove passkey/i })).toBeNull();
    expect(screen.getByRole('button', { name: /add a passkey on this device/i })).toBeTruthy();
  });

  it('adds a passkey: options → browser ceremony → verify → list refreshes', async () => {
    const create = mockWebAuthn();
    getSpy.mockResolvedValueOnce(envelope([]));
    render(<PasskeySection totpOn={false} />);

    await waitFor(() => expect(getSpy).toHaveBeenCalled());
    postSpy.mockResolvedValueOnce(envelope(registrationOptions));
    postSpy.mockResolvedValueOnce(envelope({ credentialId: 'new-cred-id' }));
    getSpy.mockResolvedValueOnce(
      envelope([
        { id: 12, credentialId: 'new-cred-id', createdAt: '2026-10-23T01:02:03Z', signCount: 0 },
      ]),
    );

    fireEvent.change(screen.getByLabelText(/current password/i), {
      target: { value: 'secret' },
    });
    fireEvent.click(screen.getByRole('button', { name: /add a passkey on this device/i }));

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    // the ceremony ran against the server-issued options
    const creationInput = create.mock.calls[0][0].publicKey;
    expect(creationInput.rp).toMatchObject({ id: 'localhost', name: 'CampusMarketplace' });
    expect(postSpy).toHaveBeenCalledWith('/auth/2fa/passkeys/registration/options');
    // the verify call carried the serialized credential JSON
    const verifyCall = postSpy.mock.calls.find(
      (c) => c[0] === '/auth/2fa/passkeys/registration/verify',
    );
    expect(verifyCall).toBeTruthy();
    const parsed = JSON.parse((verifyCall![1] as { credential: string }).credential);
    expect(parsed.id).toBe('new-cred-id');
    expect(parsed.response.attestationObject).toBeTruthy();
    // and the list refreshed afterwards
    await waitFor(() => expect(screen.getByText(/Passkey added\. It now works/)).toBeTruthy());
    expect(getSpy).toHaveBeenCalledTimes(2);
  });

  it('asks for a TOTP code in the proof when TOTP is on', async () => {
    getSpy.mockResolvedValueOnce(envelope([]));
    render(<PasskeySection totpOn={true} />);

    await waitFor(() => expect(getSpy).toHaveBeenCalled());
    expect(screen.getByLabelText(/authenticator code/i)).toBeTruthy();
  });

  it('re-proves transparently when the options call answers the step-up 403', async () => {
    mockWebAuthn();
    getSpy.mockResolvedValueOnce(envelope([]));
    render(<PasskeySection totpOn={false} />);

    await waitFor(() => expect(getSpy).toHaveBeenCalled());
    // The queue order mirrors the real flow: options → 403 step-up →
    // step-up re-proof → options retry → verify.
    postSpy
      .mockRejectedValueOnce(
        axiosFailure(403, 403, 'Sensitive action requires re-authentication: re-authentication required'),
      )
      .mockResolvedValueOnce(
        envelope({ accessToken: 'fresh-token', expiresInSeconds: 900 }),
      )
      .mockResolvedValueOnce(envelope(registrationOptions))
      .mockResolvedValueOnce(envelope({ credentialId: 'new-cred-id' }));
    getSpy.mockResolvedValueOnce(envelope([]));

    fireEvent.change(screen.getByLabelText(/current password/i), {
      target: { value: 'secret' },
    });
    fireEvent.click(screen.getByRole('button', { name: /add a passkey on this device/i }));

    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith('/auth/step-up', { currentPassword: 'secret' }),
    );
    // the options call retried exactly once after the re-proof
    const optionsCalls = postSpy.mock.calls.filter(
      (c) => c[0] === '/auth/2fa/passkeys/registration/options',
    );
    expect(optionsCalls).toHaveLength(2);
  });

  it('removes a passkey and refreshes the list', async () => {
    getSpy.mockResolvedValueOnce(
      envelope([
        { id: 11, credentialId: 'cred-aaa', createdAt: '2026-10-20T01:02:03Z', signCount: 0 },
      ]),
    );
    render(<PasskeySection totpOn={false} />);

    const remove = await screen.findByRole('button', { name: /remove passkey/i });
    deleteSpy.mockResolvedValueOnce(envelope(null));
    getSpy.mockResolvedValueOnce(envelope([]));

    fireEvent.change(screen.getByLabelText(/current password/i), {
      target: { value: 'secret' },
    });
    fireEvent.click(remove);

    await waitFor(() => expect(deleteSpy).toHaveBeenCalledWith('/auth/2fa/passkeys/11'));
    await waitFor(() => expect(screen.getByText('Passkey removed.')).toBeTruthy());
    expect(getSpy).toHaveBeenCalledTimes(2);
  });

  it('requires the password before any passkey action', async () => {
    getSpy.mockResolvedValueOnce(envelope([]));
    render(<PasskeySection totpOn={false} />);

    await waitFor(() => expect(getSpy).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: /add a passkey on this device/i }));

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(postSpy).not.toHaveBeenCalled();
  });
});
