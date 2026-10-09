import axios from 'axios';
import { type FormEvent, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { confirmPasswordReset } from '../api/auth';

/**
 * Maps a failed confirm call to UI copy (backlog #99). A weak new
 * password fails with 400 and the backend's strength reason, shown
 * verbatim (mirroring SettingsPage's password-change mapping). Unknown,
 * used and expired tokens all fail with the backend's identical 401, so
 * they map to ONE generic message — the page must not distinguish them.
 */
function describeResetError(err: unknown): string {
  if (axios.isAxiosError(err)) {
    if (err.response?.status === 401) {
      return 'This reset link is invalid or has expired. Request a new one.';
    }
    const message = (err.response?.data as { message?: string } | undefined)?.message;
    if (message) return message;
    if (!err.response) return 'Network error — is the backend running?';
  }
  return 'Password reset failed';
}

/**
 * Reset password (backlog #99): /reset-password?token=… — the token from
 * the reset link is the credential for POST
 * /api/auth/password-reset/confirm. A missing token param renders an
 * explanatory state and makes NO API call. On success the backend has
 * bumped token_version and revoked every refresh family, so the user is
 * signed out everywhere; the page navigates to /login, which renders
 * the signed-out-everywhere note from the navigation state.
 */
export default function ResetPasswordPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');

  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  if (!token) {
    return (
      <div className="mx-auto max-w-sm rounded-lg bg-white p-6 shadow-sm">
        <h1 className="mb-4 text-xl font-bold">Reset password</h1>
        <p role="alert" className="rounded bg-red-50 px-3 py-2 text-sm text-red-700">
          This reset link is missing its token, so there is nothing to confirm. Request a
          new reset link instead.
        </p>
        <p className="mt-4 text-sm text-neutral-500">
          <Link to="/forgot-password" className="text-blue-600 hover:underline">
            Request a reset link
          </Link>
        </p>
      </div>
    );
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (newPassword === '' || confirmPassword === '') {
      setError('Fill in both password fields.');
      return;
    }
    if (newPassword !== confirmPassword) {
      setError('The new passwords do not match.');
      return;
    }
    setPending(true);
    setError(null);
    try {
      await confirmPasswordReset({ token: token!, newPassword });
      navigate('/login', { replace: true, state: { passwordReset: true } });
    } catch (err) {
      setError(describeResetError(err));
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="mx-auto max-w-sm rounded-lg bg-white p-6 shadow-sm">
      <h1 className="mb-4 text-xl font-bold">Reset password</h1>
      {error && (
        <p role="alert" className="mb-4 rounded bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}
      <form onSubmit={onSubmit} className="space-y-3">
        <div>
          <label htmlFor="reset-new-password" className="mb-1 block text-sm font-medium">
            New password
          </label>
          <input
            id="reset-new-password"
            type="password"
            autoComplete="new-password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor="reset-confirm-password" className="mb-1 block text-sm font-medium">
            Confirm new password
          </label>
          <input
            id="reset-confirm-password"
            type="password"
            autoComplete="new-password"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
            className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </div>
        <button
          type="submit"
          disabled={pending}
          className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {pending ? 'Resetting…' : 'Reset password'}
        </button>
      </form>
    </div>
  );
}
