import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, getAccessToken, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';
import SettingsPage from './SettingsPage';

const postSpy = vi.spyOn(api, 'post');

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

  it('bounces anonymous users to /login without firing any request', () => {
    useAuthStore.setState({ user: null });
    renderAt('/settings');

    expect(screen.getByText('login page stub')).toBeTruthy();
    expect(postSpy).not.toHaveBeenCalled();
  });
});
