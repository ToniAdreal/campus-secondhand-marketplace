import axios from 'axios';
import { type FormEvent, useEffect, useState } from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import {
  changeEmail,
  changePassword,
  closeAccount,
  confirmDataExport,
  disableTotp,
  downloadMyData,
  enableTotp,
  recoveryCodeCount,
  setupTotp,
  withStepUpRetry,
  type TotpSetup,
} from '../api/auth';
import { setAccessToken } from '../api/client';
import { webauthnSupported } from '../api/webauthn';
import PasskeySection from '../components/PasskeySection';
import { useMyBlocks, useUnblockUser } from '../api/blocks';
import { useMyFollows, useUnfollowSeller } from '../api/follows';
import {
  useNotificationPreferences,
  useSetNotificationPreference,
} from '../api/notifications';
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

/**
 * Maps a failed change-email call to UI copy (backlog #150). A wrong
 * current password fails with the same envelope 401 as everywhere else
 * (no oracle), phrased the same way as the password form; a taken (or
 * unchanged) address fails 409 and every other failure shows the
 * backend's envelope message verbatim.
 */
function describeEmailChangeError(err: unknown): string {
  if (axios.isAxiosError(err)) {
    if (err.response?.status === 401) return 'Current password is incorrect.';
    const message = (err.response?.data as { message?: string } | undefined)?.message;
    if (message) return message;
    if (!err.response) return 'Network error — is the backend running?';
  }
  return 'Email change failed';
}

/**
 * Maps a failed account-closure call to UI copy (backlog #158). A wrong
 * password and a wrong/missing second-factor code share the backend's
 * identical 401, so the copy names both and neither (no oracle); every
 * other failure shows the backend's envelope message verbatim.
 */
function describeCloseAccountError(err: unknown): string {
  if (axios.isAxiosError(err)) {
    if (err.response?.status === 401) return 'Current password or code is incorrect.';
    const message = (err.response?.data as { message?: string } | undefined)?.message;
    if (message) return message;
    if (!err.response) return 'Network error — is the backend running?';
  }
  return 'Account closure failed';
}

/**
 * Maps a failed data-export download to UI copy (backlog #172). The
 * export is rate-limited per user, so a 429 gets its own honest copy;
 * the error body arrives as a Blob (the request asks for a blob), so
 * nothing else can be read out of it — every other failure is generic.
 */
function describeDataExportError(err: unknown): string {
  if (axios.isAxiosError(err)) {
    if (err.response?.status === 429) {
      return 'Too many downloads in a row — wait a minute, then try again.';
    }
    if (err.response?.status === 401) {
      return 'That password or code did not check out — check it and try again.';
    }
    if (err.response?.status === 423) {
      return 'Too many wrong codes in a row — two-factor confirmation is locked for a while.';
    }
    if (!err.response) return 'Network error — is the backend running?';
  }
  return 'Downloading your data failed';
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

  // Email change (backlog #150, reworked by #208). A successful submit
  // only REQUESTS the change: the backend stages the new address as
  // pending and mails it a single-use token, so this session stays
  // alive and the form is replaced by a "check your new inbox" panel —
  // the switch (and the sign-out of every session) happens only when
  // the mailed link is confirmed.
  const [newEmail, setNewEmail] = useState('');
  const [emailPassword, setEmailPassword] = useState('');
  const [emailError, setEmailError] = useState<string | null>(null);
  const [emailDone, setEmailDone] = useState<string | null>(null);
  const [emailPending, setEmailPending] = useState(false);

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

  // Account closure (backlog #158). The danger zone stays collapsed until
  // the user opens it, and the destructive call additionally requires the
  // typed confirmation below — a single stray click can never close the
  // account. Success is terminal for this tab (every session died with
  // the account), mirroring the email-change done panel.
  const [closureOpen, setClosureOpen] = useState(false);
  const [closurePassword, setClosurePassword] = useState('');
  const [closureCode, setClosureCode] = useState('');
  const [closureConfirm, setClosureConfirm] = useState('');
  const [closureError, setClosureError] = useState<string | null>(null);
  const [closureDone, setClosureDone] = useState(false);
  const [closurePending, setClosurePending] = useState(false);

  // Account data export (backlog #172). The download is a blob saved
  // under the server's filename — the page never renders the contents,
  // so nothing sensitive lands in the DOM or in component state.
  const [exportError, setExportError] = useState<string | null>(null);
  const [exportDone, setExportDone] = useState<string | null>(null);
  const [exportPending, setExportPending] = useState(false);
  // Step-up confirmation (backlog #188): the download needs a fresh
  // proof — current password, plus a two-factor code when 2FA is on —
  // exchanged for a single-use grant just before the download starts.
  const [exportPassword, setExportPassword] = useState('');
  const [exportCode, setExportCode] = useState('');

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

  async function onChangeEmail(e: FormEvent) {
    e.preventDefault();
    if (newEmail.trim() === '' || emailPassword === '') {
      setEmailError('Enter the new email address and your current password.');
      return;
    }
    setEmailPending(true);
    setEmailError(null);
    try {
      const target = newEmail.trim();
      await changeEmail({ currentPassword: emailPassword, newEmail: target });
      // Backlog #208: nothing has switched yet — the session stays
      // alive until the mailed link is confirmed, so the credential
      // stays in place and only the form gives way to the pending panel.
      setEmailPassword('');
      setEmailDone(target);
    } catch (err) {
      setEmailError(describeEmailChangeError(err));
    } finally {
      setEmailPending(false);
    }
  }

  async function onCloseAccount(e: FormEvent) {
    e.preventDefault();
    if (closurePassword === '') {
      setClosureError('Enter your current password to confirm.');
      return;
    }
    if (closureConfirm !== 'DELETE') {
      setClosureError('Type DELETE to confirm account closure.');
      return;
    }
    setClosurePending(true);
    setClosureError(null);
    try {
      // Backlog #218: account closure is freshness-gated; the form already
      // holds the proof, so a stale token re-proves transparently with
      // the same credentials and retries exactly once (withStepUpRetry).
      const code = closureCode.trim();
      const proof = code
        ? { currentPassword: closurePassword, code }
        : { currentPassword: closurePassword };
      await withStepUpRetry(() => closeAccount(proof), proof);
      // The account is gone server-side and every session with it; drop
      // this tab's credential too so no later call presents a dead token.
      setAccessToken(null);
      setClosurePassword('');
      setClosureCode('');
      setClosureConfirm('');
      setClosureDone(true);
    } catch (err) {
      setClosureError(describeCloseAccountError(err));
    } finally {
      setClosurePending(false);
    }
  }

  async function onDownloadMyData() {
    if (exportPassword === '') {
      setExportError('Enter your current password to confirm the download.');
      return;
    }
    if (totpOn && exportCode.trim() === '') {
      setExportError('Enter a two-factor code to confirm the download.');
      return;
    }
    setExportPending(true);
    setExportError(null);
    setExportDone(null);
    try {
      // Step-up first (backlog #188): the proof mints a single-use
      // grant, and the download consumes it — the grant never outlives
      // this click, and the proof fields clear on success.
      //
      // Backlog #218: the confirm endpoint is also freshness-gated — the
      // form already holds the proof, so a stale token re-proves
      // transparently with the same credentials and retries exactly once
      // (withStepUpRetry); no second password prompt.
      const proof = totpOn
        ? { currentPassword: exportPassword, code: exportCode.trim() }
        : { currentPassword: exportPassword };
      const { grant } = await withStepUpRetry(
        () => confirmDataExport(proof),
        proof,
      );
      const { blob, filename } = await downloadMyData(grant);
      setExportPassword('');
      setExportCode('');
      // Save the blob under the server filename (it carries the user id
      // and export date) through a throwaway object URL + anchor click.
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = filename;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
      setExportDone(filename);
    } catch (err) {
      setExportError(describeDataExportError(err));
    } finally {
      setExportPending(false);
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
      // Backlog #218: disabling 2FA is freshness-gated; the form already
      // holds the proof, so a stale token re-proves transparently with
      // the same credentials and retries exactly once (withStepUpRetry).
      const proof = { currentPassword: disablePassword, code: disableCode.trim() };
      await withStepUpRetry(() => disableTotp(proof), proof);
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
        <h2 className="mb-1 text-sm font-semibold">Email address</h2>
        <p className="mb-3 text-sm text-neutral-500">
          Currently {user.email}. A change is only staged until you open the
          confirmation link sent to the new address — confirming it switches
          the address and signs out every device.
        </p>
        {emailDone ? (
          <p role="status" className="rounded bg-green-50 px-3 py-2 text-sm text-green-800">
            Check your new inbox — a confirmation link was sent to {emailDone}.
            Your email address stays {user.email} until you open that link; the
            switch signs out every device.
          </p>
        ) : (
          <form onSubmit={onChangeEmail} className="space-y-3">
            {emailError && (
              <p role="alert" className="rounded bg-red-50 px-3 py-2 text-sm text-red-700">
                {emailError}
              </p>
            )}
            <div>
              <label htmlFor="settings-new-email" className="mb-1 block text-sm font-medium">
                New email address
              </label>
              <input
                id="settings-new-email"
                type="email"
                autoComplete="email"
                value={newEmail}
                onChange={(e) => setNewEmail(e.target.value)}
                className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
              />
            </div>
            <div>
              <label
                htmlFor="settings-email-password"
                className="mb-1 block text-sm font-medium"
              >
                Confirm with current password
              </label>
              <input
                id="settings-email-password"
                type="password"
                autoComplete="current-password"
                value={emailPassword}
                onChange={(e) => setEmailPassword(e.target.value)}
                className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
              />
            </div>
            <button
              type="submit"
              disabled={emailPending}
              className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {emailPending ? 'Changing…' : 'Change email'}
            </button>
          </form>
        )}
      </div>
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
        {webauthnSupported() && <PasskeySection totpOn={totpOn} />}
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
      <div className="mt-6 border-t border-neutral-200 pt-4">
        <h2 className="mb-1 text-sm font-semibold">Your data</h2>
        <p className="mb-3 text-sm text-neutral-500">
          Download a copy of your data as one JSON file: your profile, listings,
          orders, messages, saved listings and saved searches. It never contains
          your password or two-factor secret. This is a personal copy — it cannot
          be imported back or used to move your account. Because the file is the
          most complete copy of your account that exists, downloading asks for
          your current password again (and a two-factor code when 2FA is on).
        </p>
        {exportError && (
          <p role="alert" className="mb-3 rounded bg-red-50 px-3 py-2 text-sm text-red-700">
            {exportError}
          </p>
        )}
        {exportDone && (
          <p role="status" className="mb-3 rounded bg-green-50 px-3 py-2 text-sm text-green-800">
            Your data was downloaded as {exportDone}.
          </p>
        )}
        <div className="mb-3">
          <label htmlFor="settings-export-password" className="mb-1 block text-sm font-medium">
            Confirm your password
          </label>
          <input
            id="settings-export-password"
            type="password"
            autoComplete="current-password"
            value={exportPassword}
            onChange={(e) => {
              setExportPassword(e.target.value);
              setExportError(null);
            }}
            className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </div>
        {totpOn && (
          <div className="mb-3">
            <label htmlFor="settings-export-code" className="mb-1 block text-sm font-medium">
              Export confirmation code
            </label>
            <input
              id="settings-export-code"
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              value={exportCode}
              onChange={(e) => {
                setExportCode(e.target.value);
                setExportError(null);
              }}
              className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
            />
          </div>
        )}
        <button
          type="button"
          onClick={onDownloadMyData}
          disabled={exportPending}
          className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {exportPending ? 'Preparing…' : 'Download my data'}
        </button>
      </div>
      <div className="mt-6 border-t border-neutral-200 pt-4">
        <h2 className="mb-1 text-sm font-semibold">Notifications</h2>
        <p className="mb-3 text-sm text-neutral-500">
          Choose which notifications appear in your bell. Turning a type off
          stops new ones of that type — ones you already have stay until you
          delete them. Account-security alerts are always on.
        </p>
        <NotificationPreferencesSection />
      </div>
      <div className="mt-6 border-t border-neutral-200 pt-4">
        <h2 className="mb-1 text-sm font-semibold">Blocked users</h2>
        <p className="mb-3 text-sm text-neutral-500">
          Users you block cannot message you or make you offers, and their
          listings stop appearing in your browsing and search. Existing
          conversations and orders are never changed.
        </p>
        <BlockedUsersSection />
      </div>
      <div className="mt-6 border-t border-neutral-200 pt-4">
        <h2 className="mb-1 text-sm font-semibold">Following</h2>
        <p className="mb-3 text-sm text-neutral-500">
          Sellers you follow notify you in your bell when they publish a new
          listing. You can turn those notifications off per type above.
        </p>
        <FollowingSection />
      </div>
      <div className="mt-6 border-t border-red-200 pt-4">
        <h2 className="mb-1 text-sm font-semibold text-red-700">Danger zone</h2>
        <p className="mb-3 text-sm text-neutral-500">
          Closing your account is permanent. Every session is signed out, your
          available listings are withdrawn, and your username and email address
          are released. Orders you were part of stay in the other party&apos;s
          history — they are never rewritten.
        </p>
        {closureDone ? (
          <p role="status" className="rounded bg-green-50 px-3 py-2 text-sm text-green-800">
            Account closed. You have been signed out everywhere — this account can
            no longer be used to sign in.
          </p>
        ) : closureOpen ? (
          <form onSubmit={onCloseAccount} className="space-y-3">
            {closureError && (
              <p role="alert" className="rounded bg-red-50 px-3 py-2 text-sm text-red-700">
                {closureError}
              </p>
            )}
            <div>
              <label
                htmlFor="settings-closure-password"
                className="mb-1 block text-sm font-medium"
              >
                Password to confirm closure
              </label>
              <input
                id="settings-closure-password"
                type="password"
                autoComplete="current-password"
                value={closurePassword}
                onChange={(e) => setClosurePassword(e.target.value)}
                className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
              />
            </div>
            <div>
              <label htmlFor="settings-closure-code" className="mb-1 block text-sm font-medium">
                Authenticator or recovery code (only if two-factor is on)
              </label>
              <input
                id="settings-closure-code"
                type="text"
                autoComplete="one-time-code"
                value={closureCode}
                onChange={(e) => setClosureCode(e.target.value)}
                className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
              />
            </div>
            <div>
              <label
                htmlFor="settings-closure-confirm"
                className="mb-1 block text-sm font-medium"
              >
                Type DELETE to confirm
              </label>
              <input
                id="settings-closure-confirm"
                type="text"
                autoComplete="off"
                value={closureConfirm}
                onChange={(e) => setClosureConfirm(e.target.value)}
                className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
              />
            </div>
            <button
              type="submit"
              disabled={closurePending}
              className="w-full rounded bg-red-600 px-3 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
            >
              {closurePending ? 'Closing…' : 'Confirm: close my account'}
            </button>
            <button
              type="button"
              onClick={() => {
                setClosureOpen(false);
                setClosureError(null);
                setClosurePassword('');
                setClosureCode('');
                setClosureConfirm('');
              }}
              className="w-full rounded border border-neutral-300 px-3 py-2 text-sm font-medium text-neutral-700 hover:bg-neutral-50"
            >
              Keep my account
            </button>
          </form>
        ) : (
          <button
            type="button"
            onClick={() => {
              setClosureOpen(true);
              setClosureError(null);
            }}
            className="w-full rounded border border-neutral-300 px-3 py-2 text-sm font-medium text-red-700 hover:bg-red-50"
          >
            Close my account
          </button>
        )}
      </div>
    </div>
  );
}

/**
 * Per-type notification toggles (backlog #216): the catalogue — labels
 * and the locked-on security flags included — comes from the backend
 * endpoint, the one place labels live. A failed catalogue load degrades
 * to a plain note rather than breaking the settings page, mirroring
 * the blocked-users section. Security rows render checked and disabled
 * with the reason stated; every other row's toggle round-trips through
 * the mutation and the invalidated catalogue query.
 */
function NotificationPreferencesSection() {
  const catalogue = useNotificationPreferences();
  const setPreference = useSetNotificationPreference();
  // A malformed (non-array) payload is a failed load, never a render
  // crash: the section degrades to the same note as an error.
  const rows = Array.isArray(catalogue.data) ? catalogue.data : [];
  if (catalogue.isError || (catalogue.isSuccess && !Array.isArray(catalogue.data))) {
    return (
      <p className="text-sm text-neutral-500">
        Notification settings could not be loaded — everything stays on.
      </p>
    );
  }
  if (rows.length === 0) {
    return <p className="text-sm text-neutral-500">Loading notification settings…</p>;
  }
  return (
    <ul className="space-y-2">
      {rows.map((row) => (
        <li
          key={row.type}
          className="flex items-center justify-between rounded bg-neutral-50 px-3 py-2"
        >
          <label htmlFor={`notif-pref-${row.type}`} className="text-sm font-medium">
            {row.label}
            {row.security && (
              <span className="block text-xs font-normal text-neutral-500">
                Always on — account-security alerts can&apos;t be turned off.
              </span>
            )}
          </label>
          <input
            id={`notif-pref-${row.type}`}
            type="checkbox"
            checked={row.enabled}
            disabled={row.security || setPreference.isLoading}
            onChange={(e) =>
              setPreference.mutate({ type: row.type, enabled: e.target.checked })
            }
            className="h-4 w-4"
          />
        </li>
      ))}
    </ul>
  );
}

/**
 * Blocked-users list (backlog #187): the caller's blocks with an
 * unblock action per row. A failed list load degrades to the empty
 * copy rather than breaking the settings page — the block state can
 * always be re-read on the next visit.
 */
function BlockedUsersSection() {
  const blocks = useMyBlocks();
  const unblock = useUnblockUser();
  const rows = blocks.data ?? [];
  if (rows.length === 0) {
    return <p className="text-sm text-neutral-500">You have not blocked anyone.</p>;
  }
  return (
    <ul className="space-y-2">
      {rows.map((row) => (
        <li
          key={row.blockedId}
          className="flex items-center justify-between rounded bg-neutral-50 px-3 py-2"
        >
          <span className="text-sm font-medium">{row.blockedUsername}</span>
          <button
            type="button"
            onClick={() => unblock.mutate(row.blockedId)}
            disabled={unblock.isLoading}
            className="rounded border border-neutral-300 px-3 py-1 text-sm font-medium text-neutral-700 hover:bg-neutral-100 disabled:opacity-50"
          >
            Unblock
          </button>
        </li>
      ))}
    </ul>
  );
}

/**
 * Following list (backlog #217): the caller's followed sellers with an
 * unfollow action per row, newest follow first. The list carries only
 * the public username — no follower counts exist anywhere in the app.
 * A failed list load degrades to the empty copy rather than breaking
 * the settings page.
 */
function FollowingSection() {
  const follows = useMyFollows();
  const unfollow = useUnfollowSeller();
  const rows = follows.data ?? [];
  if (rows.length === 0) {
    return <p className="text-sm text-neutral-500">You are not following any sellers yet.</p>;
  }
  return (
    <ul className="space-y-2">
      {rows.map((row) => (
        <li
          key={row.sellerId}
          className="flex items-center justify-between rounded bg-neutral-50 px-3 py-2"
        >
          <Link
            to={`/seller/${row.sellerId}`}
            className="text-sm font-medium text-blue-600 hover:underline"
          >
            {row.sellerUsername}
          </Link>
          <button
            type="button"
            onClick={() => unfollow.mutate(row.sellerId)}
            disabled={unfollow.isLoading}
            className="rounded border border-neutral-300 px-3 py-1 text-sm font-medium text-neutral-700 hover:bg-neutral-100 disabled:opacity-50"
          >
            Unfollow
          </button>
        </li>
      ))}
    </ul>
  );
}
