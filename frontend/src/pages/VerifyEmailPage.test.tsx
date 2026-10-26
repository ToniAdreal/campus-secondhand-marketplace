import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api } from '../api/client';
import VerifyEmailPage from './VerifyEmailPage';

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

function renderAt(initialEntry: string) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/verify-email" element={<VerifyEmailPage />} />
        <Route path="/register" element={<div>register page stub</div>} />
        <Route path="/login" element={<div>login page stub</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('VerifyEmailPage', () => {
  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
  });

  it('missing token param renders the explanatory state and makes no API call', async () => {
    renderAt('/verify-email');

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('missing its token');
    expect(postSpy).not.toHaveBeenCalled();
  });

  it('confirms on arrival and renders the confirmed state with a login link', async () => {
    postSpy.mockResolvedValueOnce(envelope(null));
    renderAt('/verify-email?token=raw-token-abc');

    const status = await screen.findByText(/email address confirmed/i);
    expect(status).toBeTruthy();
    expect(postSpy).toHaveBeenCalledTimes(1);
    expect(postSpy).toHaveBeenCalledWith('/auth/email-verification/confirm', {
      token: 'raw-token-abc',
    });
    expect(screen.getByRole('link', { name: /back to login/i })).toBeTruthy();
  });

  it('maps the identical 400 to one generic invalid-or-expired message', async () => {
    postSpy.mockRejectedValueOnce(
      axiosFailure(400, 'invalid or expired verification token'),
    );
    // Backlog #208: the change confirm is tried as the fallback and
    // rejects the same token with its identical 401.
    postSpy.mockRejectedValueOnce(
      axiosFailure(401, 'invalid or expired email change token'),
    );
    renderAt('/verify-email?token=stale-token');

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('invalid or has expired');
    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(2));
    expect(postSpy).toHaveBeenNthCalledWith(2, '/auth/email-change/confirm', {
      token: 'stale-token',
    });
  });

  // Backlog #230: drive the page to its failed state (both confirms reject),
  // then exercise the resend control that lives in that state.
  async function renderFailed() {
    postSpy.mockRejectedValueOnce(
      axiosFailure(400, 'invalid or expired verification token'),
    );
    postSpy.mockRejectedValueOnce(
      axiosFailure(401, 'invalid or expired email change token'),
    );
    renderAt('/verify-email?token=stale-token');
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('invalid or has expired');
  }

  it('failed state renders an email field and a resend button (backlog #230)', async () => {
    await renderFailed();

    const email = screen.getByLabelText('Email');
    expect(email).toBeTruthy();
    const button = screen.getByRole('button', { name: /resend verification link/i });
    expect(button).toBeTruthy();
    // No address typed yet — the button stays disabled rather than posting blank.
    expect((button as HTMLButtonElement).disabled).toBe(true);
    expect(postSpy).toHaveBeenCalledTimes(2);
  });

  it('resend button posts the typed address and confirms a fresh link (backlog #230)', async () => {
    await renderFailed();

    fireEvent.change(screen.getByLabelText('Email'), {
      target: { value: 'alice@example.com' },
    });
    postSpy.mockResolvedValueOnce(envelope(null));
    fireEvent.click(screen.getByRole('button', { name: /resend verification link/i }));

    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith('/auth/email-verification/resend', {
        email: 'alice@example.com',
      }),
    );
    expect(await screen.findByText(/check your inbox/i)).toBeTruthy();
    expect(await screen.findByText(/fresh verification link was issued/i)).toBeTruthy();
  });

  it('resend failure maps to the generic error copy (backlog #230)', async () => {
    await renderFailed();

    fireEvent.change(screen.getByLabelText('Email'), {
      target: { value: 'alice@example.com' },
    });
    postSpy.mockRejectedValueOnce(axiosFailure(429, 'rate limit exceeded'));
    fireEvent.click(screen.getByRole('button', { name: /resend verification link/i }));

    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith('/auth/email-verification/resend', {
        email: 'alice@example.com',
      }),
    );
    // Generic copy only — the backend's 429 reason is not surfaced, and no
    // account-state hint leaks through the resend path.
    expect(await screen.findByText(/could not resend the verification link/i)).toBeTruthy();
  });

  it('confirms an email-change token through the fallback (backlog #208)', async () => {
    postSpy.mockRejectedValueOnce(
      axiosFailure(400, 'invalid or expired verification token'),
    );
    postSpy.mockResolvedValueOnce(envelope(null));
    renderAt('/verify-email?token=change-token');

    const status = await screen.findByText(/email address confirmed/i);
    expect(status).toBeTruthy();
    expect(postSpy).toHaveBeenCalledTimes(2);
    expect(postSpy).toHaveBeenNthCalledWith(2, '/auth/email-change/confirm', {
      token: 'change-token',
    });
  });
});
