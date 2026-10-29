import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, getAccessToken, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';
import SettingsPage from './SettingsPage';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');
const putSpy = vi.spyOn(api, 'put');
const deleteSpy = vi.spyOn(api, 'delete');

const user: AuthUser = { id: 5, username: 'toni', email: 'toni@example.com', roles: ['USER'] };

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function axiosFailure(status: number, message: string) {
  return {
    isAxiosError: true,
    response: { status, data: { code: status, message, data: null } },
  };
}

function renderAt(initialEntry: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="/login" element={<div>login page stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function fill(current: string, next: string, confirm: string) {
  fireEvent.change(screen.getByLabelText('Current password'), { target: { value: current } });
  fireEvent.change(screen.getByLabelText('New password'), { target: { value: next } });
  fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: confirm } });
}

describe('SettingsPage', () => {
  beforeEach(() => {
    // synchronous reset — store logout() now calls the server (would pollute spies)
    useAuthStore.setState({ user });
    setAccessToken('old-credential');
    // default: the 2FA recovery-code count fails, so the section degrades
    // to no readout — individual tests override with a resolved count
    getSpy.mockRejectedValue(new Error('count unavailable'));
  });

  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
  });

  it('settings form fields are reachable by their labels (backlog #167)', () => {
    // Regression net for the accessibility pass, same pattern as the
    // create-listing page: fields must stay bound to their labels.
    renderAt('/settings');

    expect(screen.getByLabelText('Current password')).toBeTruthy();
    expect(screen.getByLabelText('New password')).toBeTruthy();
    expect(screen.getByLabelText('Confirm new password')).toBeTruthy();
    expect(screen.getByLabelText('New email address')).toBeTruthy();
  });

  it('posts the change-password payload and stores the fresh credential + note on success', async () => {
    postSpy.mockResolvedValueOnce(
      envelope({
        accessToken: 'new-credential',
        tokenType: 'Bearer',
        expiresInSeconds: 900,
        user,
      }),
    );
    renderAt('/settings');
    fill('old-secret', 'new-strong-secret-1', 'new-strong-secret-1');
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(1));
    expect(postSpy).toHaveBeenCalledWith('/auth/password', {
      currentPassword: 'old-secret',
      newPassword: 'new-strong-secret-1',
    });
    // the backend rotated the family — this tab must run on the fresh pair
    expect(getAccessToken()).toBe('new-credential');
    const status = await screen.findByRole('status');
    expect(status.textContent).toBe(
      'Password changed. Your other devices have been signed out.',
    );
    // passwords cleared from the DOM after success
    const values = ['Current password', 'New password', 'Confirm new password'].map(
      (label) => (screen.getByLabelText(label) as HTMLInputElement).value,
    );
    expect(values).toEqual(['', '', '']);
  });

  it('maps a 401 to "Current password is incorrect." (no oracle)', async () => {
    // the backend deliberately answers a wrong current password with the
    // same 401 message as everywhere else
    postSpy.mockRejectedValueOnce(axiosFailure(401, 'invalid credentials'));
    renderAt('/settings');
    fill('wrong-secret', 'new-strong-secret-1', 'new-strong-secret-1');
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('Current password is incorrect.');
    // nothing rotated: the old credential is still in place
    expect(getAccessToken()).toBe('old-credential');
  });

  it('maps a 400 to the strength reason verbatim', async () => {
    postSpy.mockRejectedValueOnce(axiosFailure(400, 'password must be 8-72 characters'));
    renderAt('/settings');
    fill('old-secret', 'short', 'short');
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('password must be 8-72 characters');
  });

  it('blocks mismatched new passwords client-side without firing the request', () => {
    renderAt('/settings');
    fill('old-secret', 'new-strong-secret-1', 'different-secret-2');
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));

    expect(screen.getByRole('alert').textContent).toBe('The new passwords do not match.');
    expect(postSpy).not.toHaveBeenCalled();
  });

  it('shows the recovery-code count readout when the count endpoint succeeds (backlog #100)', async () => {
    getSpy.mockReset();
    getSpy.mockResolvedValue(envelope({ remaining: 7 }));
    renderAt('/settings');

    expect(await screen.findByText('7 recovery codes remaining.')).toBeTruthy();
    expect(getSpy).toHaveBeenCalledWith('/auth/2fa/recovery-codes/count');
  });

  it('count endpoint failure degrades to no readout, not a crash (backlog #100)', async () => {
    renderAt('/settings');

    // the two-factor section still renders and offers setup
    expect(
      await screen.findByRole('button', { name: 'Set up two-factor authentication' }),
    ).toBeTruthy();
    expect(screen.queryByText(/recovery codes remaining\./)).toBeNull();
    // and the password form is untouched by the failure
    expect(screen.getByRole('button', { name: 'Change password' })).toBeTruthy();
  });

  it('enable flow shows secret + otpauth URI as text, then renders the recovery codes exactly once (backlog #100)', async () => {
    getSpy.mockReset();
    getSpy.mockResolvedValue(envelope({ remaining: 3 }));
    const codes = ['AAAA-BBBB-CCCC-DDDD', 'EEEE-FFFF-GGGG-HHHH', 'IIII-JJJJ-KKKK-LLLL'];
    postSpy.mockResolvedValueOnce(
      envelope({
        secret: 'JBSWY3DPEHPK3PXP',
        otpauthUri: 'otpauth://totp/CampusMarketplace:toni?secret=JBSWY3DPEHPK3PXP',
      }),
    );
    postSpy.mockResolvedValueOnce(envelope({ recoveryCodes: codes }));
    const first = renderAt('/settings');

    fireEvent.click(
      await screen.findByRole('button', { name: 'Set up two-factor authentication' }),
    );
    expect(postSpy).toHaveBeenCalledWith('/auth/2fa/setup');
    // secret + otpauth URI rendered as text (no QR library)
    expect(await screen.findByText('JBSWY3DPEHPK3PXP')).toBeTruthy();
    expect(screen.getByText(/otpauth:\/\/totp\//)).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Authenticator code'), {
      target: { value: '123456' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Enable two-factor authentication' }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(2));
    expect(postSpy).toHaveBeenNthCalledWith(2, '/auth/2fa/enable', { code: '123456' });
    // codes + the shown-once warning
    const warning = await screen.findByText(/shown once/);
    expect(warning.textContent).toContain('store them safely');
    for (const code of codes) {
      expect(screen.getByText(code)).toBeTruthy();
    }

    // navigating away and back does NOT re-show the codes — they exist
    // only in the enable response; the remounted page shows the count
    first.unmount();
    renderAt('/settings');
    expect(await screen.findByText('3 recovery codes remaining.')).toBeTruthy();
    for (const code of codes) {
      expect(screen.queryByText(code)).toBeNull();
    }
  });

  it('disable flow: confirm step first, then posts password + code and shows the off note (backlog #121)', async () => {
    getSpy.mockReset();
    getSpy.mockResolvedValue(envelope({ remaining: 7 }));
    postSpy.mockResolvedValueOnce(envelope(null));
    renderAt('/settings');

    // the control appears because the count says 2FA is on with codes left
    fireEvent.click(
      await screen.findByRole('button', { name: 'Turn off two-factor authentication' }),
    );
    // the confirm step renders first — nothing has been posted yet
    expect(postSpy).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Confirm current password'), {
      target: { value: 's3cret-pass' },
    });
    fireEvent.change(screen.getByLabelText('Authenticator or recovery code'), {
      target: { value: '123456' },
    });
    fireEvent.click(
      screen.getByRole('button', { name: 'Confirm: turn off two-factor authentication' }),
    );

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(1));
    expect(postSpy).toHaveBeenCalledWith('/auth/2fa/disable', {
      currentPassword: 's3cret-pass',
      code: '123456',
    });
    const status = await screen.findByRole('status');
    expect(status.textContent).toBe(
      'Two-factor authentication is off. Your password alone now signs you in.',
    );
    // the control is gone and enrollment is offered again
    expect(screen.queryByRole('button', { name: 'Turn off two-factor authentication' })).toBeNull();
    expect(
      screen.getByRole('button', { name: 'Set up two-factor authentication' }),
    ).toBeTruthy();
    expect(screen.getByText('0 recovery codes remaining.')).toBeTruthy();
  });

  it('disable 401 maps to the shared "password or code" copy and keeps the form (backlog #121)', async () => {
    getSpy.mockReset();
    getSpy.mockResolvedValue(envelope({ remaining: 4 }));
    postSpy.mockRejectedValueOnce(axiosFailure(401, 'invalid credentials'));
    renderAt('/settings');

    fireEvent.click(
      await screen.findByRole('button', { name: 'Turn off two-factor authentication' }),
    );
    fireEvent.change(screen.getByLabelText('Confirm current password'), {
      target: { value: 'wr0ng-pass' },
    });
    fireEvent.change(screen.getByLabelText('Authenticator or recovery code'), {
      target: { value: '123456' },
    });
    fireEvent.click(
      screen.getByRole('button', { name: 'Confirm: turn off two-factor authentication' }),
    );

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('Current password or code is incorrect.');
    // still on: the confirm form stays open for another attempt
    expect(
      screen.getByRole('button', { name: 'Confirm: turn off two-factor authentication' }),
    ).toBeTruthy();
  });

  it('no disable control when the count says no codes remain (backlog #121)', async () => {
    getSpy.mockReset();
    getSpy.mockResolvedValue(envelope({ remaining: 0 }));
    renderAt('/settings');

    expect(
      await screen.findByRole('button', { name: 'Set up two-factor authentication' }),
    ).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Turn off two-factor authentication' })).toBeNull();
  });

  it('posts the change-email payload and shows the check-your-new-inbox pending state on success (backlog #208)', async () => {
    postSpy.mockResolvedValueOnce(envelope(null));
    renderAt('/settings');
    fireEvent.change(screen.getByLabelText('New email address'), {
      target: { value: 'toni.new@example.com' },
    });
    fireEvent.change(screen.getByLabelText('Confirm with current password'), {
      target: { value: 'old-secret' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Change email' }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(1));
    expect(postSpy).toHaveBeenCalledWith('/auth/email-change', {
      currentPassword: 'old-secret',
      newEmail: 'toni.new@example.com',
    });
    const status = await screen.findByRole('status');
    expect(status.textContent).toContain('Check your new inbox');
    expect(status.textContent).toContain('toni.new@example.com');
    expect(status.textContent).toContain('stays toni@example.com');
    // nothing has switched yet: this session's credential is still alive
    expect(getAccessToken()).toBe('old-credential');
    // the form is replaced by the pending panel — no duplicate request
    expect(screen.queryByRole('button', { name: 'Change email' })).toBeNull();
  });

  it('maps a change-email 401 to "Current password is incorrect." (backlog #150)', async () => {
    postSpy.mockRejectedValueOnce(axiosFailure(401, 'invalid credentials'));
    renderAt('/settings');
    fireEvent.change(screen.getByLabelText('New email address'), {
      target: { value: 'toni.new@example.com' },
    });
    fireEvent.change(screen.getByLabelText('Confirm with current password'), {
      target: { value: 'wrong-secret' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Change email' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('Current password is incorrect.');
    // nothing changed: the old credential is still in place
    expect(getAccessToken()).toBe('old-credential');
  });

  it('maps a change-email 409 to the backend message verbatim (backlog #150)', async () => {
    postSpy.mockRejectedValueOnce(axiosFailure(409, 'email is already registered'));
    renderAt('/settings');
    fireEvent.change(screen.getByLabelText('New email address'), {
      target: { value: 'taken@example.com' },
    });
    fireEvent.change(screen.getByLabelText('Confirm with current password'), {
      target: { value: 'old-secret' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Change email' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('email is already registered');
  });

  it('opens the re-auth dialog on a change-email step-up 403 and retries exactly once after proving (backlog #272)', async () => {
    const stepUpMessage =
      'Sensitive action requires re-authentication: re-authentication required';
    postSpy
      .mockRejectedValueOnce(axiosFailure(403, stepUpMessage))
      .mockResolvedValueOnce(
        envelope({ accessToken: 'fresh-token', tokenType: 'Bearer', expiresInSeconds: 900 }),
      )
      .mockResolvedValueOnce(envelope(null));
    renderAt('/settings');
    fireEvent.change(screen.getByLabelText('New email address'), {
      target: { value: 'toni.new@example.com' },
    });
    fireEvent.change(screen.getByLabelText('Confirm with current password'), {
      target: { value: 'old-secret' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Change email' }));

    // the step-up dialog opens instead of an error banner
    const dialog = await screen.findByRole('dialog');
    expect(screen.queryByRole('alert')).toBeNull();

    // the page's own password form shares the "Current password" label,
    // so the modal's field is addressed inside the dialog
    fireEvent.change(within(dialog).getByLabelText('Current password'), {
      target: { value: 'pw' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }));

    // proof posted, token swapped, the same staged change retried exactly once
    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith('/auth/step-up', { currentPassword: 'pw' }),
    );
    expect(getAccessToken()).toBe('fresh-token');
    const status = await screen.findByRole('status');
    expect(status.textContent).toContain('Check your new inbox');
    expect(status.textContent).toContain('toni.new@example.com');
    expect(postSpy).toHaveBeenCalledTimes(3); // attempt + proof + the single retry
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('a second step-up 403 on the email retry surfaces as an error instead of reopening the dialog (backlog #272)', async () => {
    const stepUpMessage =
      'Sensitive action requires re-authentication: re-authentication required';
    postSpy
      .mockRejectedValueOnce(axiosFailure(403, stepUpMessage))
      .mockResolvedValueOnce(
        envelope({ accessToken: 'fresh-token', tokenType: 'Bearer', expiresInSeconds: 900 }),
      )
      .mockRejectedValueOnce(axiosFailure(403, stepUpMessage));
    renderAt('/settings');
    fireEvent.change(screen.getByLabelText('New email address'), {
      target: { value: 'toni.new@example.com' },
    });
    fireEvent.change(screen.getByLabelText('Confirm with current password'), {
      target: { value: 'old-secret' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Change email' }));

    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Current password'), {
      target: { value: 'pw' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }));

    // exactly one retry happened; its 403 surfaces verbatim, no dialog loop
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('re-authentication required');
    expect(postSpy).toHaveBeenCalledTimes(3);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('posts the change-username payload, swaps the stored user and keeps the session alive (backlog #256)', async () => {
    postSpy.mockResolvedValueOnce(
      envelope({ id: 5, username: 'toni2', email: 'toni@example.com', roles: ['USER'] }),
    );
    renderAt('/settings');
    fireEvent.change(screen.getByLabelText('New username'), {
      target: { value: 'toni2' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Change username' }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(1));
    expect(postSpy).toHaveBeenCalledWith('/auth/username', { username: 'toni2' });
    const status = await screen.findByRole('status');
    expect(status.textContent).toContain('Username changed to toni2');
    // the header follows the new name at once, and the session is untouched
    expect(useAuthStore.getState().user?.username).toBe('toni2');
    expect(getAccessToken()).toBe('old-credential');
    expect(screen.queryByRole('button', { name: 'Change username' })).toBeNull();
  });

  it('maps a change-username 409 to the backend message verbatim (backlog #256)', async () => {
    postSpy.mockRejectedValueOnce(axiosFailure(409, 'username is already taken'));
    renderAt('/settings');
    fireEvent.change(screen.getByLabelText('New username'), {
      target: { value: 'taken' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Change username' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('username is already taken');
    expect(useAuthStore.getState().user?.username).toBe('toni');
  });

  it('opens the re-auth dialog on a change-username step-up 403 and retries exactly once after proving (backlog #261)', async () => {
    const stepUpMessage =
      'Sensitive action requires re-authentication: re-authentication required';
    postSpy
      .mockRejectedValueOnce(axiosFailure(403, stepUpMessage))
      .mockResolvedValueOnce(
        envelope({ accessToken: 'fresh-token', tokenType: 'Bearer', expiresInSeconds: 900 }),
      )
      .mockResolvedValueOnce(
        envelope({ id: 5, username: 'toni2', email: 'toni@example.com', roles: ['USER'] }),
      );
    renderAt('/settings');
    fireEvent.change(screen.getByLabelText('New username'), {
      target: { value: 'toni2' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Change username' }));

    // the step-up dialog opens instead of an error banner
    const dialog = await screen.findByRole('dialog');
    expect(screen.queryByRole('alert')).toBeNull();

    // the page's own password form shares the "Current password" label,
    // so the modal's field is addressed inside the dialog
    fireEvent.change(within(dialog).getByLabelText('Current password'), {
      target: { value: 'pw' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }));

    // proof posted, token swapped, the same rename retried exactly once
    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith('/auth/step-up', { currentPassword: 'pw' }),
    );
    expect(getAccessToken()).toBe('fresh-token');
    const status = await screen.findByRole('status');
    expect(status.textContent).toContain('Username changed to toni2');
    expect(postSpy).toHaveBeenCalledTimes(3); // attempt + proof + the single retry
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('a second step-up 403 on the username retry surfaces as an error instead of reopening the dialog (backlog #261)', async () => {
    const stepUpMessage =
      'Sensitive action requires re-authentication: re-authentication required';
    postSpy
      .mockRejectedValueOnce(axiosFailure(403, stepUpMessage))
      .mockResolvedValueOnce(
        envelope({ accessToken: 'fresh-token', tokenType: 'Bearer', expiresInSeconds: 900 }),
      )
      .mockRejectedValueOnce(axiosFailure(403, stepUpMessage));
    renderAt('/settings');
    fireEvent.change(screen.getByLabelText('New username'), {
      target: { value: 'toni2' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Change username' }));

    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Current password'), {
      target: { value: 'pw' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }));

    // exactly one retry happened; its 403 surfaces verbatim, no dialog loop
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('re-authentication required');
    expect(postSpy).toHaveBeenCalledTimes(3);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('closes the account from the danger zone after the typed confirmation (backlog #158)', async () => {
    deleteSpy.mockResolvedValueOnce(envelope(null));
    renderAt('/settings');
    fireEvent.click(screen.getByRole('button', { name: 'Close my account' }));
    fireEvent.change(screen.getByLabelText('Password to confirm closure'), {
      target: { value: 'old-secret' },
    });
    fireEvent.change(screen.getByLabelText('Type DELETE to confirm'), {
      target: { value: 'DELETE' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm: close my account' }));

    await waitFor(() => expect(deleteSpy).toHaveBeenCalledTimes(1));
    expect(deleteSpy).toHaveBeenCalledWith('/auth/me', {
      data: { currentPassword: 'old-secret' },
    });
    const status = await screen.findByRole('status');
    expect(status.textContent).toContain('Account closed.');
    // the backend killed this session too: the in-memory credential is gone
    expect(getAccessToken()).toBeNull();
  });

  it('refuses account closure without the typed DELETE confirmation (backlog #158)', async () => {
    renderAt('/settings');
    fireEvent.click(screen.getByRole('button', { name: 'Close my account' }));
    fireEvent.change(screen.getByLabelText('Password to confirm closure'), {
      target: { value: 'old-secret' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm: close my account' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('Type DELETE to confirm account closure.');
    expect(deleteSpy).not.toHaveBeenCalled();
  });

  it('maps an account-closure 401 to the shared no-oracle copy (backlog #158)', async () => {
    deleteSpy.mockRejectedValueOnce(axiosFailure(401, 'invalid credentials'));
    renderAt('/settings');
    fireEvent.click(screen.getByRole('button', { name: 'Close my account' }));
    fireEvent.change(screen.getByLabelText('Password to confirm closure'), {
      target: { value: 'wrong-secret' },
    });
    fireEvent.change(screen.getByLabelText('Type DELETE to confirm'), {
      target: { value: 'DELETE' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm: close my account' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('Current password or code is incorrect.');
    expect(getAccessToken()).toBe('old-credential');
  });

  it('bounces anonymous users to /login without firing any request', () => {
    useAuthStore.setState({ user: null });
    renderAt('/settings');

    expect(screen.getByText('login page stub')).toBeTruthy();
    expect(postSpy).not.toHaveBeenCalled();
  });

  it('downloads the data export as a blob saved under the server filename (backlog #172)', async () => {
    const createObjectURL = vi.fn(() => 'blob:mock-export');
    const revokeObjectURL = vi.fn();
    Object.assign(URL, { createObjectURL, revokeObjectURL });
    const anchorClick = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => {});
    const blob = new Blob(['{"profile":{}}'], { type: 'application/json' });
    // The mount-time recovery-code count keeps failing (the beforeEach
    // default); the export GET is the one that resolves.
    getSpy.mockImplementation((url: string) =>
      String(url).includes('/users/me/export')
        ? Promise.resolve({
            data: blob,
            headers: {
              'content-disposition':
                'attachment; filename="marketplace-data-user-5-2026-10-15.json"',
            },
          } as never)
        : Promise.reject(new Error('count unavailable')),
    );
    // Backlog #188: the step-up confirm mints the grant the download
    // then presents; the password field must be filled first.
    postSpy.mockResolvedValueOnce(
      envelope({ grant: 'grant-1', expiresAt: '2026-10-15T00:05:00Z' }),
    );
    renderAt('/settings');
    fireEvent.change(screen.getByLabelText('Confirm your password'), {
      target: { value: 's3cret-pass' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Download my data' }));

    const status = await screen.findByRole('status');
    expect(status.textContent).toBe(
      'Your data was downloaded as marketplace-data-user-5-2026-10-15.json.',
    );
    expect(postSpy).toHaveBeenCalledWith('/users/me/export/confirm', {
      currentPassword: 's3cret-pass',
    });
    expect(getSpy).toHaveBeenCalledWith('/users/me/export', {
      responseType: 'blob',
      headers: { 'X-Export-Grant': 'grant-1' },
    });
    expect(createObjectURL).toHaveBeenCalledWith(blob);
    expect(anchorClick).toHaveBeenCalledTimes(1);
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock-export');
    anchorClick.mockRestore();
  });

  it('maps a data-export 429 to the wait-a-minute copy (backlog #172)', async () => {
    getSpy.mockImplementation((url: string) =>
      String(url).includes('/users/me/export')
        ? Promise.reject(axiosFailure(429, 'too many requests'))
        : Promise.reject(new Error('count unavailable')),
    );
    postSpy.mockResolvedValueOnce(
      envelope({ grant: 'grant-1', expiresAt: '2026-10-15T00:05:00Z' }),
    );
    renderAt('/settings');
    fireEvent.change(screen.getByLabelText('Confirm your password'), {
      target: { value: 's3cret-pass' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Download my data' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe(
      'Too many downloads in a row — wait a minute, then try again.',
    );
  });

  it('stops at the step-up confirm when the password is wrong (backlog #188)', async () => {
    postSpy.mockRejectedValueOnce(axiosFailure(401, 'invalid credentials'));
    renderAt('/settings');
    fireEvent.change(screen.getByLabelText('Confirm your password'), {
      target: { value: 'wr0ng-pass' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Download my data' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe(
      'That password or code did not check out — check it and try again.',
    );
    expect(postSpy).toHaveBeenCalledWith('/users/me/export/confirm', {
      currentPassword: 'wr0ng-pass',
    });
    // The download itself was never attempted without a grant.
    expect(
      getSpy.mock.calls.some((call) => String(call[0]).includes('/users/me/export')),
    ).toBe(false);
  });

  it('renders the notification catalogue with security types locked on and round-trips a toggle (backlog #216)', async () => {
    // The catalogue is server state: mutating flips what the next
    // catalogue read returns, exactly like the real backend.
    let savedSearchEnabled = true;
    const catalogue = () => [
      {
        type: 'SAVED_SEARCH_MATCH',
        label: 'Saved-search matches',
        security: false,
        enabled: savedSearchEnabled,
      },
      {
        type: 'LOGIN_NEW_DEVICE',
        label: 'New-device sign-in alert',
        security: true,
        enabled: true,
      },
    ];
    getSpy.mockImplementation((url: unknown) =>
      String(url).includes('/notifications/preferences')
        ? Promise.resolve(envelope(catalogue()))
        : Promise.reject(new Error('count unavailable')),
    );
    putSpy.mockImplementation((_url: unknown, body: unknown) => {
      savedSearchEnabled = (body as { enabled: boolean }).enabled;
      return Promise.resolve(
        envelope({
          type: 'SAVED_SEARCH_MATCH',
          label: 'Saved-search matches',
          security: false,
          enabled: savedSearchEnabled,
        }),
      );
    });
    renderAt('/settings');

    const toggle = (await screen.findByLabelText('Saved-search matches')) as HTMLInputElement;
    expect(toggle.type).toBe('checkbox');
    expect(toggle.checked).toBe(true);
    // Security alerts are locked on: checked, disabled, reason shown.
    const securityToggle = screen.getByLabelText(/New-device sign-in alert/) as HTMLInputElement;
    expect(securityToggle.checked).toBe(true);
    expect(securityToggle.disabled).toBe(true);
    expect(screen.getByText(/account-security alerts can't be turned off/)).toBeTruthy();

    fireEvent.click(toggle);
    await waitFor(() =>
      expect(putSpy).toHaveBeenCalledWith('/notifications/preferences/SAVED_SEARCH_MATCH', {
        enabled: false,
      }),
    );
    // The re-rendered state comes from the invalidated catalogue
    // refetch, not a local flip.
    await waitFor(() =>
      expect((screen.getByLabelText('Saved-search matches') as HTMLInputElement).checked).toBe(
        false,
      ),
    );
  });
});

describe('SettingsPage following list (#217)', () => {
  beforeEach(() => {
    useAuthStore.setState({
      user: { id: 5, username: 'toni', email: 'toni@example.com', roles: ['USER'] },
    });
    setAccessToken('old-credential');
    getSpy.mockImplementation((url: string) => {
      if (url === '/users/me/follows') {
        return Promise.resolve(
          envelope([
            {
              sellerId: 3,
              sellerUsername: 'campus-seller',
              createdAt: '2026-10-23T00:00:00Z',
            },
          ]),
        );
      }
      // Every other settings fetch degrades to empty — the sections
      // render their empty copy, only the Following section is asserted.
      return Promise.resolve(envelope([]));
    });
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('renders the followed sellers with an unfollow action', async () => {
    renderAt('/settings');

    await waitFor(() => expect(getSpy).toHaveBeenCalledWith('/users/me/follows'));
    expect(screen.getByText('campus-seller')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Unfollow' })).toBeTruthy();
  });

  it('unfollow fires DELETE on the follow endpoint', async () => {
    renderAt('/settings');
    await waitFor(() => expect(getSpy).toHaveBeenCalledWith('/users/me/follows'));

    deleteSpy.mockResolvedValueOnce(envelope(null));
    fireEvent.click(screen.getByRole('button', { name: 'Unfollow' }));

    await waitFor(() => expect(deleteSpy).toHaveBeenCalledWith('/users/me/follows/3'));
  });

  it('renders the empty copy when the caller follows nobody', async () => {
    getSpy.mockImplementation(() => Promise.resolve(envelope([])));
    renderAt('/settings');

    await waitFor(() => expect(getSpy).toHaveBeenCalledWith('/users/me/follows'));
    expect(screen.getByText('You are not following any sellers yet.')).toBeTruthy();
  });

  describe('step-up re-auth (backlog #218)', () => {
    const stepUpMessage =
      'Sensitive action requires re-authentication: re-authentication required';

    it('transparently re-proves and retries account closure once on the 403 step-up envelope', async () => {
      deleteSpy
        .mockRejectedValueOnce(axiosFailure(403, stepUpMessage))
        .mockResolvedValueOnce(envelope(null));
      postSpy.mockResolvedValueOnce(
        envelope({ accessToken: 'fresh-token', tokenType: 'Bearer', expiresInSeconds: 900 }),
      );
      renderAt('/settings');
      fireEvent.click(screen.getByRole('button', { name: 'Close my account' }));
      fireEvent.change(screen.getByLabelText('Password to confirm closure'), {
        target: { value: 'old-secret' },
      });
      fireEvent.change(screen.getByLabelText('Type DELETE to confirm'), {
        target: { value: 'DELETE' },
      });
      fireEvent.click(screen.getByRole('button', { name: 'Confirm: close my account' }));

      // the typed proof is reused — no dialog asks twice
      await waitFor(() =>
        expect(postSpy).toHaveBeenCalledWith('/auth/step-up', {
          currentPassword: 'old-secret',
        }),
      );
      await waitFor(() => expect(deleteSpy).toHaveBeenCalledTimes(2));
      expect(screen.queryByRole('dialog')).toBeNull();
      const status = await screen.findByRole('status');
      expect(status.textContent).toContain('Account closed.');
      // the account is gone: the fresh token is dropped like the old one
      expect(getAccessToken()).toBeNull();
    });

    it('transparently re-proves and retries the export confirm once on the 403 step-up envelope', async () => {
      const blob = new Blob(['{"profile":{}}'], { type: 'application/json' });
      getSpy.mockImplementation((url: string) =>
        String(url).includes('/users/me/export')
          ? Promise.resolve({ data: blob, headers: {} } as never)
          : Promise.reject(new Error('count unavailable')),
      );
      postSpy
        .mockRejectedValueOnce(axiosFailure(403, stepUpMessage))
        .mockResolvedValueOnce(
          envelope({ accessToken: 'fresh-token', tokenType: 'Bearer', expiresInSeconds: 900 }),
        )
        .mockResolvedValueOnce(
          envelope({ grant: 'grant-1', expiresAt: '2026-10-15T00:05:00Z' }),
        );
      renderAt('/settings');
      fireEvent.change(screen.getByLabelText('Confirm your password'), {
        target: { value: 's3cret-pass' },
      });
      fireEvent.click(screen.getByRole('button', { name: 'Download my data' }));

      await waitFor(() =>
        expect(postSpy).toHaveBeenCalledWith('/auth/step-up', {
          currentPassword: 's3cret-pass',
        }),
      );
      await waitFor(() =>
        expect(postSpy).toHaveBeenCalledWith('/users/me/export/confirm', {
          currentPassword: 's3cret-pass',
        }),
      );
      // confirm was attempted twice (attempt + single retry), never a third
      const confirms = postSpy.mock.calls.filter((call) => call[0] === '/users/me/export/confirm');
      expect(confirms).toHaveLength(2);
      expect(screen.queryByRole('dialog')).toBeNull();
      const status = await screen.findByRole('status');
      expect(status.textContent).toContain('Your data was downloaded');
    });
  });
});
