import axios from 'axios';
import { type FormEvent, useEffect, useState } from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import {
  changePassword,
  disableTotp,
  enableTotp,
  recoveryCodeCount,
  setupTotp,
  type TotpSetup,
} from '../api/auth';
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
 * Maps a failed disable-2FA call to UI copy (backlog #121). A wrong
 * password and a wrong code share the backend's identical 401, so the
 * copy names both and neither (no oracle); every other failure shows
 * the backend's envelope message verbatim (e.g. the 422 "not enabled").
 */
function describeDisableTotpError(err: unknown): string {
  if (axios.isAxiosError(err)) {
    if (err.response?.status === 401) return 'Current password or code is incorrect.';
    const message = (err.response?.data as { message?: string } | undefined)?.message;
    if (message) return message;
    if (!err.response) return 'Network error — is the backend running?';
  }
  return 'Turning off two-factor authentication failed';
}

/** Envelope message for the two-factor calls (setup/enable), verbatim. */
function describeTotpError(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const message = (err.response?.data as { message?: string } | undefined)?.message;
    if (message) return message;
    if (!err.response) return 'Network error — is the backend running?';
  }
  return 'Two-factor setup failed';
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

  // Two-factor enrollment (backlog #100). The recovery codes live ONLY in
  // this state, straight from the enable response — they are shown once
  // and can never be fetched back, so nothing here re-reads them.
  const [totpSetup, setTotpSetup] = useState<TotpSetup | null>(null);
  const [totpCode, setTotpCode] = useState('');
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);
  const [remainingCodes, setRemainingCodes] = useState<number | null>(null);
  const [totpError, setTotpError] = useState<string | null>(null);
  const [totpPending, setTotpPending] = useState(false);

  // Two-factor disable (backlog #121). The page has no dedicated "is 2FA
  // on" readout: it treats 2FA as on when the count endpoint reports
  // codes remaining or this session just enabled it. The disable control
  // stays hidden otherwise; a stale guess would surface the backend's
  // honest 422, never a fake success.
  const [totpOn, setTotpOn] = useState(false);
  const [disableOpen, setDisableOpen] = useState(false);
  const [disablePassword, setDisablePassword] = useState('');
  const [disableCode, setDisableCode] = useState('');
  const [disableError, setDisableError] = useState<string | null>(null);
  const [disableDone, setDisableDone] = useState(false);
  const [disablePending, setDisablePending] = useState(false);

  useEffect(() => {
    if (!user) return;
    let cancelled = false;
    recoveryCodeCount()
      .then((remaining) => {
        if (!cancelled) {
          setRemainingCodes(remaining);
          if (remaining > 0) setTotpOn(true);
        }
      })
      .catch(() => {
        // Degrade to no readout — a failed count must never crash or
        // block the settings page (backlog #100).
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user?.id]);

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

  async function onSetupTotp() {
    setTotpPending(true);
    setTotpError(null);
    setRecoveryCodes(null);
    try {
      setTotpSetup(await setupTotp());
      setTotpCode('');
    } catch (err) {
      setTotpError(describeTotpError(err));
    } finally {
      setTotpPending(false);
    }
  }

  async function onEnableTotp(e: FormEvent) {
    e.preventDefault();
    if (totpCode.trim() === '') {
      setTotpError('Enter the 6-digit code from your authenticator app.');
      return;
    }
    setTotpPending(true);
    setTotpError(null);
    try {
      const result = await enableTotp({ code: totpCode.trim() });
      // Shown once: keep the codes from THIS response and drop the setup
      // secret; the remaining readout is derived from the same response,
      // never from a re-fetch of the codes (there is none).
      setRecoveryCodes(result.recoveryCodes);
      setRemainingCodes(result.recoveryCodes.length);
      setTotpOn(true);
      setDisableDone(false);
      setTotpSetup(null);
      setTotpCode('');
    } catch (err) {
      setTotpError(describeTotpError(err));
    } finally {
      setTotpPending(false);
    }
  }

  async function onDisableTotp(e: FormEvent) {
    e.preventDefault();
    if (disablePassword === '' || disableCode.trim() === '') {
      setDisableError('Enter your current password and a code to confirm.');
      return;
    }
    setDisablePending(true);
    setDisableError(null);
    try {
      await disableTotp({ currentPassword: disablePassword, code: disableCode.trim() });
      // 2FA is off server-side: the secret and every recovery code are
      // gone, so the local readouts reset to match — and this session
      // deliberately keeps working (the backend revokes nothing here).
      setTotpOn(false);
      setRecoveryCodes(null);
      setRemainingCodes(0);
      setDisablePassword('');
      setDisableCode('');
      setDisableOpen(false);
      setDisableDone(true);
    } catch (err) {
      setDisableError(describeDisableTotpError(err));
    } finally {
      setDisablePending(false);
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
      <div className="mt-6 border-t border-neutral-200 pt-4">
        <h2 className="mb-1 text-sm font-semibold">Two-factor authentication</h2>
        <p className="mb-3 text-sm text-neutral-500">
          Add a 6-digit authenticator code as a second step at login.
        </p>
        {remainingCodes !== null && !recoveryCodes && (
          <p className="mb-3 text-sm text-neutral-600">
            {remainingCodes} recovery code{remainingCodes === 1 ? '' : 's'} remaining.
          </p>
        )}
        {totpError && (
          <p role="alert" className="mb-3 rounded bg-red-50 px-3 py-2 text-sm text-red-700">
            {totpError}
          </p>
        )}
        {recoveryCodes ? (
          <div>
            <p className="mb-2 text-sm font-medium text-neutral-700">
              Two-factor authentication is on. These recovery codes are shown once — store
              them safely. Each one can replace an authenticator code at login, exactly
              once.
            </p>
            <ul className="space-y-1">
              {recoveryCodes.map((code) => (
                <li key={code}>
                  <code className="text-sm">{code}</code>
                </li>
              ))}
            </ul>
          </div>
        ) : totpSetup ? (
          <form onSubmit={onEnableTotp} className="space-y-3">
            <p className="text-sm text-neutral-600">
              Add this secret to your authenticator app, then enter the 6-digit code it
              shows. No QR code here — enter the secret or the otpauth URI as text.
            </p>
            <p className="break-all text-sm">
              Secret: <code>{totpSetup.secret}</code>
            </p>
            <p className="break-all text-sm">
              otpauth URI: <code>{totpSetup.otpauthUri}</code>
            </p>
            <div>
              <label htmlFor="settings-totp-code" className="mb-1 block text-sm font-medium">
                Authenticator code
              </label>
              <input
                id="settings-totp-code"
                type="text"
                autoComplete="one-time-code"
                value={totpCode}
                onChange={(e) => setTotpCode(e.target.value)}
                className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
              />
            </div>
            <button
              type="submit"
              disabled={totpPending}
              className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {totpPending ? 'Enabling…' : 'Enable two-factor authentication'}
            </button>
          </form>
        ) : (
          <button
            type="button"
            onClick={onSetupTotp}
            disabled={totpPending}
            className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {totpPending ? 'Setting up…' : 'Set up two-factor authentication'}
          </button>
        )}
        {disableDone && (
          <p role="status" className="mt-3 rounded bg-green-50 px-3 py-2 text-sm text-green-800">
            Two-factor authentication is off. Your password alone now signs you in.
          </p>
        )}
        {totpOn && !totpSetup && (
          <div className="mt-4 border-t border-neutral-200 pt-3">
            {disableError && (
              <p role="alert" className="mb-3 rounded bg-red-50 px-3 py-2 text-sm text-red-700">
                {disableError}
              </p>
            )}
            {disableOpen ? (
              <form onSubmit={onDisableTotp} className="space-y-3">
                <p className="text-sm text-neutral-600">
                  Turning this off removes the second step at login. To confirm it is
                  you, enter your current password and a current authenticator code —
                  or one of your unused recovery codes (using one here consumes it).
                </p>
                <div>
                  <label
                    htmlFor="settings-disable-password"
                    className="mb-1 block text-sm font-medium"
                  >
                    Confirm current password
                  </label>
                  <input
                    id="settings-disable-password"
                    type="password"
                    autoComplete="current-password"
                    value={disablePassword}
                    onChange={(e) => setDisablePassword(e.target.value)}
                    className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
                  />
                </div>
                <div>
                  <label
                    htmlFor="settings-disable-code"
                    className="mb-1 block text-sm font-medium"
                  >
                    Authenticator or recovery code
                  </label>
                  <input
                    id="settings-disable-code"
                    type="text"
                    autoComplete="one-time-code"
                    value={disableCode}
                    onChange={(e) => setDisableCode(e.target.value)}
                    className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
                  />
                </div>
                <button
                  type="submit"
                  disabled={disablePending}
                  className="w-full rounded bg-red-600 px-3 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
                >
                  {disablePending
                    ? 'Turning off…'
                    : 'Confirm: turn off two-factor authentication'}
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setDisableOpen(false);
                    setDisableError(null);
                    setDisablePassword('');
                    setDisableCode('');
                  }}
                  className="w-full rounded border border-neutral-300 px-3 py-2 text-sm font-medium text-neutral-700 hover:bg-neutral-50"
                >
                  Keep two-factor authentication
                </button>
              </form>
            ) : (
              <button
                type="button"
                onClick={() => {
                  setDisableOpen(true);
                  setDisableDone(false);
                  setDisableError(null);
                }}
                className="w-full rounded border border-neutral-300 px-3 py-2 text-sm font-medium text-red-700 hover:bg-red-50"
              >
                Turn off two-factor authentication
              </button>
            )}
          </div>
        )}
      </div>
      <div className="mt-6 border-t border-neutral-200 pt-4">
        <h2 className="mb-1 text-sm font-semibold">Sessions</h2>
        <p className="text-sm text-neutral-500">
          See every device signed in as you and revoke individual sessions on the{' '}
          <Link to="/settings/sessions" className="text-blue-600 hover:underline">
            My sessions
          </Link>{' '}
          page.
        </p>
      </div>
    </div>
  );
}
