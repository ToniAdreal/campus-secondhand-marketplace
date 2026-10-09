import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api } from '../api/client';
import ForgotPasswordPage from './ForgotPasswordPage';

const postSpy = vi.spyOn(api, 'post');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function renderAt() {
  return render(
    <MemoryRouter initialEntries={['/forgot-password']}>
      <Routes>
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/login" element={<div>login page stub</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

async function submit(identifier: string) {
  fireEvent.change(screen.getByLabelText(/username or email/i), {
    target: { value: identifier },
  });
  fireEvent.click(screen.getByRole('button', { name: /request reset link/i }));
  return screen.findByRole('status');
}

describe('ForgotPasswordPage', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('blocks an empty submit without calling the API', async () => {
    renderAt();

    fireEvent.click(screen.getByRole('button', { name: /request reset link/i }));

    expect((await screen.findByRole('alert')).textContent).toBe(
      'Username or email is required.',
    );
    expect(postSpy).not.toHaveBeenCalled();
  });

  it('renders the identical success state for an existing-account identifier', async () => {
    postSpy.mockResolvedValueOnce(envelope(null));
    renderAt();

    const status = await submit('toni@example.com');

    expect(postSpy).toHaveBeenCalledWith('/auth/password-reset', {
      usernameOrEmail: 'toni@example.com',
    });
    // enumeration-identical copy: conditional phrasing, never "email sent"
    expect(status.textContent).toContain('If an account exists');
    expect(status.textContent).toContain('a reset link was issued');
    expect(status.textContent).not.toContain('sent');
  });

  it('renders the identical success state for an unknown identifier', async () => {
    // the backend answers the same 200 for unknown identifiers, so the
    // page cannot (and must not) render anything different
    postSpy.mockResolvedValueOnce(envelope(null));
    renderAt();

    const status = await submit('nobody@example.com');

    expect(postSpy).toHaveBeenCalledWith('/auth/password-reset', {
      usernameOrEmail: 'nobody@example.com',
    });
    expect(status.textContent).toContain('If an account exists');
    expect(status.textContent).toContain('a reset link was issued');
  });

  it('shows the envelope message when the request itself fails (e.g. 429)', async () => {
    postSpy.mockRejectedValueOnce({
      isAxiosError: true,
      response: {
        status: 429,
        data: { code: 429, message: 'too many requests', data: null },
      },
    });
    renderAt();

    fireEvent.change(screen.getByLabelText(/username or email/i), {
      target: { value: 'toni' },
    });
    fireEvent.click(screen.getByRole('button', { name: /request reset link/i }));

    expect((await screen.findByRole('alert')).textContent).toBe('too many requests');
    expect(screen.queryByRole('status')).toBeNull();
  });
});
