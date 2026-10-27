import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api } from '../api/client';
import type { AuthUser } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';
import RegisterPage from './RegisterPage';

const postSpy = vi.spyOn(api, 'post');

const fakeUser: AuthUser = { id: 7, username: 'alice', email: 'alice@example.com', roles: ['USER'] };

function envelope(data: unknown, status = 200) {
  return { status, data: { code: 0, message: 'ok', data } } as never;
}

function axiosFailure(status: number, message: string) {
  return {
    isAxiosError: true,
    response: { status, data: { code: status, message, data: null } },
  };
}

function renderRegister() {
  return render(
    <MemoryRouter initialEntries={['/register']}>
      <Routes>
        <Route path="/" element={<div>home probe</div>} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/login" element={<div>login page stub</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

function fillForm() {
  fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'alice' } });
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'alice@example.com' } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 's3cret-pass1' } });
}

describe('RegisterPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
  });

  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
    useAuthStore.setState({ user: null });
  });

  it('verification-required outcome renders the check-your-inbox state and starts no session', async () => {
    postSpy.mockResolvedValueOnce(
      envelope({ user: fakeUser, emailVerificationRequired: true }, 201),
    );
    renderRegister();
    fillForm();
    fireEvent.click(screen.getByRole('button', { name: /create account/i }));

    const status = await screen.findByRole('status');
    expect(status.textContent).toContain('alice@example.com');
    expect(status.textContent).toContain('no session has been started yet');
    expect(postSpy).toHaveBeenCalledWith('/auth/register', {
      username: 'alice',
      email: 'alice@example.com',
      password: 's3cret-pass1',
    });
    // No session: the store stays logged out.
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('resend button posts the address and confirms a fresh link was issued', async () => {
    postSpy.mockResolvedValueOnce(
      envelope({ user: fakeUser, emailVerificationRequired: true }, 201),
    );
    renderRegister();
    fillForm();
    fireEvent.click(screen.getByRole('button', { name: /create account/i }));
    await screen.findByRole('status');

    postSpy.mockResolvedValueOnce(envelope(null));
    fireEvent.click(screen.getByRole('button', { name: /resend verification link/i }));

    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith('/auth/email-verification/resend', {
        email: 'alice@example.com',
      }),
    );
    expect(await screen.findByText(/fresh verification link was issued/i)).toBeTruthy();
  });

  it('authenticated outcome (gate off) signs in and navigates home', async () => {
    postSpy.mockResolvedValueOnce(
      envelope({
        accessToken: 'tok-abc',
        tokenType: 'Bearer',
        expiresInSeconds: 900,
        user: fakeUser,
      }),
    );
    renderRegister();
    fillForm();
    fireEvent.click(screen.getByRole('button', { name: /create account/i }));

    expect(await screen.findByText('home probe')).toBeTruthy();
    expect(useAuthStore.getState().user).toEqual(fakeUser);
  });

  it('maps a 409 duplicate to the backend message verbatim', async () => {
    postSpy.mockRejectedValueOnce(axiosFailure(409, 'username is already taken'));
    renderRegister();
    fillForm();
    fireEvent.click(screen.getByRole('button', { name: /create account/i }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('username is already taken');
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('a 429 with Retry-After shows the wait copy, not the generic failure (backlog #242)', async () => {
    postSpy.mockRejectedValueOnce({
      isAxiosError: true,
      response: {
        status: 429,
        headers: { 'retry-after': '60' },
        data: { code: 429, message: 'too many requests', data: null },
      },
    });
    renderRegister();
    fillForm();
    fireEvent.click(screen.getByRole('button', { name: /create account/i }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('Too many attempts — try again in 60 seconds');
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('a 429 without Retry-After falls back to the generic copy (backlog #242)', async () => {
    postSpy.mockRejectedValueOnce(axiosFailure(429, 'too many requests'));
    renderRegister();
    fillForm();
    fireEvent.click(screen.getByRole('button', { name: /create account/i }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('Registration failed');
    expect(useAuthStore.getState().user).toBeNull();
  });
});
