import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { api } from '../api/client';
import ResetPasswordPage from './ResetPasswordPage';

const postSpy = vi.spyOn(api, 'post');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function axiosFailure(status: number, message: string) {
  return {
    isAxiosError: true,
    response: { status, data: { code: status, message, data: null } },
  };
}

let loginState: unknown = undefined;
function LoginProbe() {
  loginState = useLocation().state;
  return <div>login page stub</div>;
}

function renderAt(initialEntry: string) {
  loginState = undefined;
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        <Route path="/forgot-password" element={<div>forgot page stub</div>} />
        <Route path="/login" element={<LoginProbe />} />
      </Routes>
    </MemoryRouter>,
  );
}

function fill(next: string, confirm: string) {
  fireEvent.change(screen.getByLabelText('New password'), { target: { value: next } });
  fireEvent.change(screen.getByLabelText('Confirm new password'), {
    target: { value: confirm },
  });
}

describe('ResetPasswordPage', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('missing token param renders the explanatory state and makes no API call', async () => {
    renderAt('/reset-password');

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('missing its token');
    expect(screen.queryByRole('button', { name: /reset password/i })).toBeNull();
    expect(screen.getByRole('link', { name: /request a reset link/i })).toBeTruthy();
    expect(postSpy).not.toHaveBeenCalled();
  });

  it('happy path posts token + new password and navigates to /login with the reset flag', async () => {
    postSpy.mockResolvedValueOnce(envelope(null));
    renderAt('/reset-password?token=raw-token-abc');
    fill('new-strong-secret-1', 'new-strong-secret-1');
    fireEvent.click(screen.getByRole('button', { name: /^reset password$/i }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(1));
    expect(postSpy).toHaveBeenCalledWith('/auth/password-reset/confirm', {
      token: 'raw-token-abc',
      newPassword: 'new-strong-secret-1',
    });
    // success lands on /login; the flag drives the signed-out-everywhere note
    expect(await screen.findByText('login page stub')).toBeTruthy();
    expect(loginState).toEqual({ passwordReset: true });
  });

  it('maps a 400 to the backend strength reason verbatim', async () => {
    postSpy.mockRejectedValueOnce(
      axiosFailure(400, 'password must contain a letter and a digit'),
    );
    renderAt('/reset-password?token=raw-token-abc');
    fill('weak', 'weak');
    fireEvent.click(screen.getByRole('button', { name: /^reset password$/i }));

    expect((await screen.findByRole('alert')).textContent).toBe(
      'password must contain a letter and a digit',
    );
    expect(screen.queryByText('login page stub')).toBeNull();
  });

  it('maps a 401 to one generic invalid-or-expired message (unknown/used/expired are identical)', async () => {
    postSpy.mockRejectedValueOnce(axiosFailure(401, 'invalid or expired reset token'));
    renderAt('/reset-password?token=stale-token');
    fill('new-strong-secret-1', 'new-strong-secret-1');
    fireEvent.click(screen.getByRole('button', { name: /^reset password$/i }));

    expect((await screen.findByRole('alert')).textContent).toBe(
      'This reset link is invalid or has expired. Request a new one.',
    );
    expect(screen.queryByText('login page stub')).toBeNull();
  });

  it('blocks mismatched passwords without calling the API', async () => {
    renderAt('/reset-password?token=raw-token-abc');
    fill('new-strong-secret-1', 'different-secret-1');
    fireEvent.click(screen.getByRole('button', { name: /^reset password$/i }));

    expect((await screen.findByRole('alert')).textContent).toBe(
      'The new passwords do not match.',
    );
    expect(postSpy).not.toHaveBeenCalled();
  });
});
