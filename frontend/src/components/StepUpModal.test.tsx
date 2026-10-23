import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import StepUpModal from './StepUpModal';
import { getAccessToken, setAccessToken } from '../api/client';

// The modal's only network call is the step-up proof; everything else is
// local. Mock at the module boundary so the token swap is observable.
const stepUpMock = vi.fn();
vi.mock('../api/auth', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/auth')>();
  return { ...original, stepUp: (...args: unknown[]) => stepUpMock(...args) };
});

const props = {
  open: true,
  totpEnabled: false,
  actionLabel: 'revoke all other sessions',
  onProven: vi.fn(),
  onCancel: vi.fn(),
};

describe('StepUpModal (backlog #218)', () => {
  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
    setAccessToken(null);
  });

  it('renders nothing when closed', () => {
    render(<StepUpModal {...props} open={false} />);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('shows only the password field when 2FA is off', () => {
    render(<StepUpModal {...props} totpEnabled={false} />);

    expect(screen.getByLabelText('Current password')).toBeTruthy();
    expect(screen.queryByLabelText('Two-factor code')).toBeNull();
    expect(screen.getByText(/revoke all other sessions/)).toBeTruthy();
  });

  it('shows the code field when 2FA is on', () => {
    render(<StepUpModal {...props} totpEnabled={true} />);

    expect(screen.getByLabelText('Current password')).toBeTruthy();
    expect(screen.getByLabelText('Two-factor code')).toBeTruthy();
  });

  it('requires the code before submitting when 2FA is on', async () => {
    render(<StepUpModal {...props} totpEnabled={true} />);

    fireEvent.change(screen.getByLabelText('Current password'), {
      target: { value: 'pw' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }));

    expect(screen.getByRole('alert').textContent).toContain('two-factor code');
    expect(stepUpMock).not.toHaveBeenCalled();
  });

  it('re-proves, swaps the token, and calls onProven on success', async () => {
    const onProven = vi.fn();
    stepUpMock.mockResolvedValueOnce({
      accessToken: 'fresh-token',
      tokenType: 'Bearer',
      expiresInSeconds: 900,
    });
    render(<StepUpModal {...props} onProven={onProven} />);

    fireEvent.change(screen.getByLabelText('Current password'), {
      target: { value: 'pw' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => expect(onProven).toHaveBeenCalledTimes(1));
    expect(stepUpMock).toHaveBeenCalledWith({ currentPassword: 'pw' });
    expect(getAccessToken()).toBe('fresh-token');
  });

  it('includes the code in the proof when 2FA is on', async () => {
    stepUpMock.mockResolvedValueOnce({ accessToken: 't' });
    render(<StepUpModal {...props} totpEnabled={true} onProven={vi.fn()} />);

    fireEvent.change(screen.getByLabelText('Current password'), {
      target: { value: 'pw' },
    });
    fireEvent.change(screen.getByLabelText('Two-factor code'), {
      target: { value: '123456' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => expect(stepUpMock).toHaveBeenCalledTimes(1));
    expect(stepUpMock).toHaveBeenCalledWith({
      currentPassword: 'pw',
      code: '123456',
    });
  });

  it('maps a wrong proof to user-facing copy and stays open', async () => {
    const onProven = vi.fn();
    stepUpMock.mockRejectedValueOnce({
      response: { status: 401, data: { code: 401, message: 'bad proof' } },
    });
    render(<StepUpModal {...props} onProven={onProven} />);

    fireEvent.change(screen.getByLabelText('Current password'), {
      target: { value: 'wrong' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toContain('incorrect');
    expect(onProven).not.toHaveBeenCalled();
    expect(getAccessToken()).toBeNull();
  });

  it('calls onCancel without proving when cancelled', () => {
    const onCancel = vi.fn();
    render(<StepUpModal {...props} onCancel={onCancel} />);

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(stepUpMock).not.toHaveBeenCalled();
  });

  it('resets its fields when reopened after a failed attempt', async () => {
    stepUpMock.mockRejectedValueOnce({
      response: { status: 401, data: { code: 401, message: 'bad proof' } },
    });
    const { rerender } = render(<StepUpModal {...props} />);

    fireEvent.change(screen.getByLabelText('Current password'), {
      target: { value: 'wrong' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }));
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());

    rerender(<StepUpModal {...props} open={false} />);
    rerender(<StepUpModal {...props} open={true} />);

    expect(screen.queryByRole('alert')).toBeNull();
    expect((screen.getByLabelText('Current password') as HTMLInputElement).value).toBe('');
  });
});
