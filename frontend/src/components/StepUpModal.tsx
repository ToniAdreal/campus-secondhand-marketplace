import { useEffect, useState, type FormEvent } from 'react';
import { describeStepUpError, stepUp } from '../api/auth';
import { setAccessToken } from '../api/client';

export interface StepUpModalProps {
  /** Whether the dialog is visible. */
  open: boolean;
  /**
   * Whether the account has TOTP 2FA enabled — when true the modal also
   * asks for a second-factor code, mirroring the backend's step-up proof
   * shape (backlog #218). Fed from GET /api/auth/me's `totpEnabled`.
   */
  totpEnabled: boolean;
  /**
   * Human label for the sensitive action being re-authorized, e.g.
   * "revoke all other sessions" — shown in the dialog copy so the user
   * knows exactly what the re-proof unlocks.
   */
  actionLabel: string;
  /**
   * Called after the fresh credential is installed in the in-memory token
   * store; the caller retries the gated action exactly once. Failures of
   * the proof itself surface inside the modal, never here.
   */
  onProven: () => void;
  onCancel: () => void;
}

/**
 * Re-authentication dialog for sensitive actions whose Bearer token's last
 * credential proof is too old (backlog #218). The backend answered the
 * action with the dedicated 403 step-up envelope; the modal re-proves the
 * current password (plus a TOTP/recovery code when 2FA is on) via
 * POST /api/auth/step-up, swaps the fresh access token into memory, and
 * hands control back to the caller for the single retry.
 *
 * Actions whose form already collected the proof (data export, TOTP
 * disable, account closure) do not use this dialog — they re-proof
 * transparently with the typed credentials via `withStepUpRetry`.
 */
export default function StepUpModal({
  open,
  totpEnabled,
  actionLabel,
  onProven,
  onCancel,
}: StepUpModalProps) {
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // A reopened dialog starts clean — a stale wrong-password error from a
  // previous attempt must never greet the user.
  useEffect(() => {
    if (open) {
      setPassword('');
      setCode('');
      setPending(false);
      setError(null);
    }
  }, [open ]);

  if (!open) return null;

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (password === '') {
      setError('Enter your current password to confirm.');
      return;
    }
    if (totpEnabled && code.trim() === '') {
      setError('Enter a two-factor code to confirm.');
      return;
    }
    setPending(true);
    setError(null);
    try {
      const fresh = await stepUp(
        totpEnabled
          ? { currentPassword: password, code: code.trim() }
          : { currentPassword: password },
      );
      setAccessToken(fresh.accessToken);
      onProven();
    } catch (err) {
      setError(describeStepUpError(err));
    } finally {
      setPending(false);
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Confirm your identity"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
    >
      <form
        onSubmit={onSubmit}
        className="w-full max-w-sm rounded-lg bg-white p-6 shadow-lg"
      >
        <h2 className="mb-1 text-lg font-bold">Confirm your identity</h2>
        <p className="mb-4 text-sm text-neutral-500">
          For your security, please re-enter your credentials before we {actionLabel}.
          This session will stay signed in.
        </p>
        <div className="mb-3">
          <label htmlFor="step-up-password" className="mb-1 block text-sm font-medium">
            Current password
          </label>
          <input
            id="step-up-password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </div>
        {totpEnabled && (
          <div className="mb-3">
            <label htmlFor="step-up-code" className="mb-1 block text-sm font-medium">
              Two-factor code
            </label>
            <input
              id="step-up-code"
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
            />
          </div>
        )}
        {error && (
          <p role="alert" className="mb-3 rounded bg-red-50 px-3 py-2 text-sm text-red-700">
            {error}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            disabled={pending}
            className="rounded border border-neutral-300 px-4 py-2 text-sm font-medium text-neutral-700 disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={pending}
            className="rounded bg-neutral-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
          >
            {pending ? 'Verifying…' : 'Verify'}
          </button>
        </div>
      </form>
    </div>
  );
}
