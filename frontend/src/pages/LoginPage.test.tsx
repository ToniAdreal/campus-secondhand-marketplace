import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { api, getAccessToken, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';
import LoginPage from './LoginPage';

const postSpy = vi.spyOn(api, 'post');

const fakeUser: AuthUser = { id: 5, username: 'toni', email: 'toni@example.com', roles: ['USER'] };

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function authSuccess() {
  return envelope({
    accessToken: 'tok-abc',
    tokenType: 'Bearer',
    expiresInSeconds: 900,
    user: fakeUser,
  });
}

function authFailure401() {
  return {
    isAxiosError: true,
    response: {
      status: 401,
      data: { code: 401, message: 'invalid credentials', data: null },
    },
  };
}

let currentPath = '';
function LocationProbe() {
  currentPath = useLocation().pathname;
  return null;
}

function renderLogin(initialEntry: string | { pathname: string; state?: unknown }) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <LocationProbe />
      <Routes>
        <Route path="/" element={<div>home probe</div>} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/forgot-password" element={<div>forgot page stub</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('LoginPage', () => {
  beforeEach(() => {
    currentPath = '';
    localStorage.clear();
    // synchronous local-state reset — the store's logout() now hits the
    // server, which teardowns must not do (it would pollute the api.post spy)
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  it('renders the form and blocks empty submits without calling the API', async () => {
    renderLogin('/login');

    expect(screen.getByLabelText(/username or email/i)).toBeTruthy();
    const password = screen.getByLabelText(/password/i) as HTMLInputElement;
    expect(password.type).toBe('password');

    fireEvent.click(screen.getByRole('button', { name: /^login$/i }));

    expect((await screen.findByRole('alert')).textContent).toBe(
      'Username or email and password are required.',
    );
    expect(postSpy).not.toHaveBeenCalled();
  });

  it('successful login posts credentials, stores user + in-memory token, and navigates home', async () => {
    postSpy.mockResolvedValueOnce(authSuccess());
    renderLogin('/login');

    fireEvent.change(screen.getByLabelText(/username or email/i), { target: { value: 'toni' } });
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: /^login$/i }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(1));
    expect(postSpy).toHaveBeenCalledWith('/auth/login', {
      usernameOrEmail: 'toni',
      password: 'secret',
    });

    // store holds the user, token lives in memory only — never localStorage
    expect(useAuthStore.getState().user).toEqual(fakeUser);
    expect(getAccessToken()).toBe('tok-abc');
    expect(localStorage.length).toBe(0);

    await waitFor(() => expect(currentPath).toBe('/'));
    expect(screen.getByText('home probe')).toBeTruthy();
  });

  it('shows the envelope message on 401 and stores nothing', async () => {
    postSpy.mockRejectedValueOnce(authFailure401());
    renderLogin('/login');

    fireEvent.change(screen.getByLabelText(/username or email/i), { target: { value: 'toni' } });
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'wrong' } });
    fireEvent.click(screen.getByRole('button', { name: /^login$/i }));

    expect((await screen.findByRole('alert')).textContent).toBe('invalid credentials');
    expect(useAuthStore.getState().user).toBeNull();
    expect(getAccessToken()).toBeNull();
    expect(currentPath).toBe('/login');
  });

  it('links to /forgot-password (backlog #99)', () => {
    renderLogin('/login');

    const link = screen.getByRole('link', { name: /forgot password/i });
    expect(link.getAttribute('href')).toBe('/forgot-password');
  });

  it('shows the signed-out-everywhere note when arriving from a completed reset', () => {
    renderLogin({ pathname: '/login', state: { passwordReset: true } });

    const status = screen.getByRole('status');
    expect(status.textContent).toContain('Password reset complete.');
    expect(status.textContent).toContain('signed out everywhere');
  });

  it('202 challenge switches to the code step; a TOTP code completes the login (backlog #100)', async () => {
    postSpy.mockResolvedValueOnce({
      status: 202,
      data: {
        code: 0,
        message: 'ok',
        data: { challenge: 'chal-123', expiresAt: '2026-10-09T05:00:00Z' },
      },
    } as never);
    postSpy.mockResolvedValueOnce(authSuccess());
    renderLogin('/login');

    fireEvent.change(screen.getByLabelText(/username or email/i), { target: { value: 'toni' } });
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: /^login$/i }));

    // code step appears, and nothing is stored yet — no token pair was issued
    const codeField = await screen.findByLabelText(/two-factor code/i);
    expect(useAuthStore.getState().user).toBeNull();
    expect(getAccessToken()).toBeNull();

    fireEvent.change(codeField, { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: /verify code/i }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(2));
    expect(postSpy).toHaveBeenNthCalledWith(2, '/auth/2fa/authenticate', {
      challenge: 'chal-123',
      code: '123456',
    });
    expect(useAuthStore.getState().user).toEqual(fakeUser);
    expect(getAccessToken()).toBe('tok-abc');
    await waitFor(() => expect(currentPath).toBe('/'));
  });

  it('the same code field accepts a recovery code (backlog #100)', async () => {
    postSpy.mockResolvedValueOnce({
      status: 202,
      data: {
        code: 0,
        message: 'ok',
        data: { challenge: 'chal-456', expiresAt: '2026-10-09T05:00:00Z' },
      },
    } as never);
    postSpy.mockResolvedValueOnce(authSuccess());
    renderLogin('/login');

    fireEvent.change(screen.getByLabelText(/username or email/i), { target: { value: 'toni' } });
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: /^login$/i }));

    const codeField = await screen.findByLabelText(/two-factor code/i);
    fireEvent.change(codeField, { target: { value: 'AAAA-BBBB-CCCC-DDDD' } });
    fireEvent.click(screen.getByRole('button', { name: /verify code/i }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(2));
    expect(postSpy).toHaveBeenNthCalledWith(2, '/auth/2fa/authenticate', {
      challenge: 'chal-456',
      code: 'AAAA-BBBB-CCCC-DDDD',
    });
    await waitFor(() => expect(currentPath).toBe('/'));
    expect(useAuthStore.getState().user).toEqual(fakeUser);
  });

  it('a wrong code shows the identical 401 copy and stores nothing (backlog #100)', async () => {
    postSpy.mockResolvedValueOnce({
      status: 202,
      data: {
        code: 0,
        message: 'ok',
        data: { challenge: 'chal-789', expiresAt: '2026-10-09T05:00:00Z' },
      },
    } as never);
    postSpy.mockRejectedValueOnce(authFailure401());
    renderLogin('/login');

    fireEvent.change(screen.getByLabelText(/username or email/i), { target: { value: 'toni' } });
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: /^login$/i }));

    const codeField = await screen.findByLabelText(/two-factor code/i);
    fireEvent.change(codeField, { target: { value: '000000' } });
    fireEvent.click(screen.getByRole('button', { name: /verify code/i }));

    expect((await screen.findByRole('alert')).textContent).toBe('invalid credentials');
    expect(useAuthStore.getState().user).toBeNull();
    expect(getAccessToken()).toBeNull();
    expect(currentPath).toBe('/login');
  });

  it('an already-logged-in user never sees the form', () => {
    useAuthStore.getState().login(fakeUser, 'tok-abc');
    renderLogin('/login');

    expect(currentPath).toBe('/');
    expect(screen.queryByRole('button', { name: /^login$/i })).toBeNull();
  });
});
