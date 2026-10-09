import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, getAccessToken, setAccessToken } from '../api/client';
import type { AuthSession, AuthUser } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';
import SessionsPage from './SessionsPage';

const getSpy = vi.spyOn(api, 'get');
const deleteSpy = vi.spyOn(api, 'delete');
const postSpy = vi.spyOn(api, 'post');
const confirmSpy = vi.spyOn(window, 'confirm');

const user: AuthUser = { id: 5, username: 'toni', email: 'toni@example.com', roles: ['USER'] };

const currentSession: AuthSession = {
  id: 'family-current',
  userAgent: 'Mozilla/5.0 (X11; Linux x86_64) Chrome/126.0',
  ipAddress: '203.0.113.7',
  createdAt: '2026-10-08T01:00:00Z',
  lastActiveAt: '2026-10-09T01:30:00Z',
  current: true,
};

// a pre-capture session: no User-Agent was stored, so the page must fall
// back to "Unknown device" instead of rendering a blank label
const otherSession: AuthSession = {
  id: 'family-other',
  userAgent: null,
  ipAddress: '198.51.100.23',
  createdAt: '2026-10-07T12:00:00Z',
  lastActiveAt: '2026-10-08T12:00:00Z',
  current: false,
};

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
          <Route path="/settings/sessions" element={<SessionsPage />} />
          <Route path="/settings" element={<div>settings page stub</div>} />
          <Route path="/login" element={<div>login page stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SessionsPage', () => {
  beforeEach(() => {
    // synchronous reset — store logout() calls the server (would pollute spies)
    useAuthStore.setState({ user });
    setAccessToken('tok');
    confirmSpy.mockReturnValue(true);
  });

  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
  });

  it('renders the session list with device, IP, last-active and the current flag', async () => {
    getSpy.mockResolvedValue(envelope([currentSession, otherSession]));
    renderAt('/settings/sessions');

    // both rows render: the raw User-Agent as the device label, and the
    // "Unknown device" fallback for the session with no stored label
    await waitFor(() =>
      expect(screen.getByText('Mozilla/5.0 (X11; Linux x86_64) Chrome/126.0')).toBeTruthy(),
    );
    expect(screen.getByText('Unknown device')).toBeTruthy();
    expect(screen.getByText((content) => content.includes('203.0.113.7'))).toBeTruthy();
    expect(screen.getByText((content) => content.includes('198.51.100.23'))).toBeTruthy();
    expect(screen.getAllByText((content) => content.startsWith('Last active:'))).toHaveLength(2);

    // exactly one row is flagged as this device — the current session
    expect(screen.getAllByText('This device')).toHaveLength(1);

    expect(getSpy).toHaveBeenCalledWith('/auth/sessions');
  });

  it('revoking another session fires DELETE and drops only that row', async () => {
    getSpy.mockResolvedValueOnce(envelope([currentSession, otherSession]));
    // the post-revoke refetch (invalidation) sees the surviving list
    getSpy.mockResolvedValue(envelope([currentSession]));
    deleteSpy.mockResolvedValue(envelope(null));
    renderAt('/settings/sessions');

    await waitFor(() => expect(screen.getByText('Unknown device')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Revoke session on Unknown device' }));

    await waitFor(() =>
      expect(deleteSpy).toHaveBeenCalledWith('/auth/sessions/family-other'),
    );
    // the revoked row is gone; the current session's row survives, and no
    // confirmation was needed for a non-current session
    await waitFor(() => expect(screen.queryByText('Unknown device')).toBeNull());
    expect(screen.getByText('Mozilla/5.0 (X11; Linux x86_64) Chrome/126.0')).toBeTruthy();
    expect(confirmSpy).not.toHaveBeenCalled();
    // still signed in on this device — no logout call, user intact
    expect(postSpy).not.toHaveBeenCalled();
    expect(useAuthStore.getState().user).toEqual(user);
  });

  it('revoking the current session asks first; declining fires no request', async () => {
    getSpy.mockResolvedValue(envelope([currentSession, otherSession]));
    confirmSpy.mockReturnValue(false);
    renderAt('/settings/sessions');

    await waitFor(() =>
      expect(screen.getByText('Mozilla/5.0 (X11; Linux x86_64) Chrome/126.0')).toBeTruthy(),
    );
    fireEvent.click(
      screen.getByRole('button', { name: /Revoke session on Mozilla/ }),
    );

    expect(confirmSpy).toHaveBeenCalledTimes(1);
    expect(deleteSpy).not.toHaveBeenCalled();
    // still on the page, still signed in
    expect(screen.queryByText('login page stub')).toBeNull();
    expect(useAuthStore.getState().user).toEqual(user);
  });

  it('revoking the current session (confirmed) signs this tab out to /login', async () => {
    getSpy.mockResolvedValue(envelope([currentSession, otherSession]));
    deleteSpy.mockResolvedValue(envelope(null));
    renderAt('/settings/sessions');

    await waitFor(() =>
      expect(screen.getByText('Mozilla/5.0 (X11; Linux x86_64) Chrome/126.0')).toBeTruthy(),
    );
    fireEvent.click(
      screen.getByRole('button', { name: /Revoke session on Mozilla/ }),
    );

    await waitFor(() =>
      expect(deleteSpy).toHaveBeenCalledWith('/auth/sessions/family-current'),
    );
    // local state cleared and bounced to /login — WITHOUT the all-sessions
    // server logout (the other session must survive)
    await waitFor(() => expect(screen.getByText('login page stub')).toBeTruthy());
    expect(useAuthStore.getState().user).toBeNull();
    expect(getAccessToken()).toBeNull();
    expect(postSpy).not.toHaveBeenCalled();
  });

  it('bounces anonymous users to /login without firing any request', () => {
    useAuthStore.setState({ user: null });
    renderAt('/settings/sessions');

    expect(screen.getByText('login page stub')).toBeTruthy();
    expect(getSpy).not.toHaveBeenCalled();
    expect(deleteSpy).not.toHaveBeenCalled();
  });

  it('a 401 mid-session redirects to /login', async () => {
    getSpy.mockRejectedValue(axiosFailure(401, 'invalid or expired credential'));
    renderAt('/settings/sessions');

    await waitFor(() => expect(screen.getByText('login page stub')).toBeTruthy());
  });

  it('shows the empty state when the caller has no live sessions', async () => {
    getSpy.mockResolvedValue(envelope([]));
    renderAt('/settings/sessions');

    await waitFor(() => expect(screen.getByText('No active sessions.')).toBeTruthy());
  });
});
