import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, getAccessToken, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';
import SettingsPage from './SettingsPage';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');

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

  it('bounces anonymous users to /login without firing any request', () => {
    useAuthStore.setState({ user: null });
    renderAt('/settings');

    expect(screen.getByText('login page stub')).toBeTruthy();
    expect(postSpy).not.toHaveBeenCalled();
  });
});
