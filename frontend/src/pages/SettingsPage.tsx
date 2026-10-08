import axios from 'axios';
import { type FormEvent, useState } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { changePassword } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';

/**
 * Maps a failed change-password call to UI copy. A wrong current password
 * fails with the same envelope 401 as everywhere else (the backend gives no
 * oracle); the only identity question on this form is the current password,
 * so it is safe to phrase the 401 that way. A weak new password fails with
 * 400 and the backend's strength reason, which we show verbatim.
 */
function describePasswordChangeError(err: unknown): string {
  if (axios.isAxiosError(err)) {
    if (err.response?.status === 401) return 'Current password is incorrect.';
    const message = (err.response?.data as { message?: string } | undefined)?.message;
    if (message) return message;
    if (!err.response) return 'Network error — is the backend running?';
  }
  return 'Password change failed';
}

/**
 * Settings / change password. The backend rotates the whole refresh-token
 * family on POST /api/auth/password, so after a successful change every
 * other device's session is dead — the page says so honestly. The response
 * carries the fresh bearer credential for THIS session, which is stored
 * through the store's login() so this tab keeps working.
 */
export default function SettingsPage() {
  const location = useLocation();
  const user = useAuthStore((s) => s.user);
  const storeLogin = useAuthStore((s) => s.login);

  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const [pending, setPending] = useState(false);

  if (!user) {
    // Anonymous bounce before any hook fires — no wasted request, and the
    // 401-on-refresh path in the store already covers mid-session expiry.
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (currentPassword === '' || newPassword === '' || confirmPassword === '') {
      setError('Fill in all three password fields.');
      return;
    }
    if (newPassword !== confirmPassword) {
      setError('The new passwords do not match.');
      return;
    }
    setPending(true);
    setError(null);
    setSuccess(false);
    try {
      const result = await changePassword({ currentPassword, newPassword });
      // The current session gets a fresh pair; keep it stored so this tab
      // stays logged in after the rotation.
      storeLogin(result.user, result.accessToken);
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
      setSuccess(true);
    } catch (err) {
      setError(describePasswordChangeError(err));
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="mx-auto max-w-sm rounded-lg bg-white p-6 shadow-sm">
      <h1 className="mb-1 text-xl font-bold">Settings</h1>
      <p className="mb-4 text-sm text-neutral-500">
        Signed in as <span className="font-medium text-neutral-700">{user.username}</span>
      </p>
      {error && (
        <p role="alert" className="mb-4 rounded bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}
      {success && (
        <p role="status" className="mb-4 rounded bg-green-50 px-3 py-2 text-sm text-green-800">
          Password changed. Your other devices have been signed out.
        </p>
      )}
      <form onSubmit={onSubmit} className="space-y-3">
        <div>
          <label htmlFor="settings-current-password" className="mb-1 block text-sm font-medium">
            Current password
          </label>
          <input
            id="settings-current-password"
            type="password"
            autoComplete="current-password"
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
            className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor="settings-new-password" className="mb-1 block text-sm font-medium">
            New password
          </label>
          <input
            id="settings-new-password"
            type="password"
            autoComplete="new-password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor="settings-confirm-password" className="mb-1 block text-sm font-medium">
            Confirm new password
          </label>
          <input
            id="settings-confirm-password"
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
          {pending ? 'Changing…' : 'Change password'}
        </button>
      </form>
    </div>
  );
}
