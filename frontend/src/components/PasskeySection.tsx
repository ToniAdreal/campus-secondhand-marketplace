import axios from 'axios';
import {
  REQUEST_TIMEOUT_MESSAGE,
  isRequestTimeout,
} from '../api/client';
import { type FormEvent, useEffect, useState } from 'react';
import {
  deletePasskey,
  finishPasskeyRegistration,
  listPasskeys,
  renamePasskey,
  startPasskeyRegistration,
  withStepUpRetry,
  type PasskeyInfo,
} from '../api/auth';
import {
  registrationOptionsToCreationOptions,
  serializeRegistrationCredential,
} from '../api/webauthn';

/** Maps a failed passkey call to UI copy (backlog #220). */
function describePasskeyError(err: unknown): string {
  if (isRequestTimeout(err)) return REQUEST_TIMEOUT_MESSAGE;
  if (axios.isAxiosError(err)) {
    if (err.response?.status === 401)
      return 'The passkey proof was not accepted — no change was made.';
    if (err.response?.status === 409) return 'This passkey is already enrolled.';
    const message = (err.response?.data as { message?: string } | undefined)?.message;
    if (message) return message;
    if (!err.response) return 'Network error — is the backend running?';
  }
  return 'Passkey operation failed';
}

function formatEnrolledAt(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString();
}

/**
 * The row label (backlog #241): the friendly name when the row has
 * one, otherwise the pre-#241 date label — a row is never rendered
 * blank, and pre-#241 rows (name null) keep exactly the old label.
 */
function displayPasskeyName(pk: PasskeyInfo): string {
  const name = pk.name?.trim();
  return name ? name : `Passkey added ${formatEnrolledAt(pk.createdAt)}`;
}

/**
 * Passkey management inside the two-factor section (backlog #220):
 * WebAuthn passkeys as an alternative second factor next to TOTP.
 * Rendered only where the browser supports WebAuthn — the caller gates
 * on `webauthnSupported()`, so there is no passkey UI on browsers that
 * cannot do the ceremony.
 *
 * Enrolment and removal both demand a fresh credential proof (current
 * password, plus a TOTP code when TOTP is on) through the same
 * `withStepUpRetry` pattern as the other freshness-gated settings
 * actions: a stale token re-proves transparently with the typed
 * credentials and retries exactly once. The list itself is a plain
 * authenticated read.
 *
 * Backlog #241: each row carries a friendly name — given at enrolment
 * (optional) or edited inline (Rename) — and the remove button's
 * aria-label names the row, so a holder with several passkeys cannot
 * delete the wrong one by mistake. Rows without a name (pre-#241 rows
 * and blank renames) render the enrolment-date label instead.
 */
export default function PasskeySection({ totpOn }: { totpOn: boolean }) {
  const [passkeys, setPasskeys] = useState<PasskeyInfo[] | null>(null);
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [renamingId, setRenamingId] = useState<number | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    let cancelled = false;
    listPasskeys()
      .then((rows) => {
        if (!cancelled) setPasskeys(rows);
      })
      .catch(() => {
        // Degrade to no readout — a failed list must never crash or
        // block the settings page.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  function proof() {
    return totpOn
      ? { currentPassword: password, code: code.trim() }
      : { currentPassword: password };
  }

  function requireProof(): boolean {
    if (password === '') {
      setError('Enter your current password to manage passkeys.');
      return false;
    }
    if (totpOn && code.trim() === '') {
      setError('Enter a two-factor code to manage passkeys.');
      return false;
    }
    return true;
  }

  async function onAdd(e: FormEvent) {
    e.preventDefault();
    if (!requireProof()) return;
    setPending(true);
    setError(null);
    setDone(null);
    try {
      const p = proof();
      // Backlog #218: enrolment is freshness-gated; the form already
      // holds the proof, so a stale token re-proves transparently with
      // the same credentials and retries exactly once (withStepUpRetry).
      // The verify call is separately gated: the options-call retry just
      // refreshed the token, and a challenge the gate rejected stays
      // live, so the same ceremony JSON retries cleanly.
      const options = await withStepUpRetry(() => startPasskeyRegistration(), p);
      const credential = (await navigator.credentials.create({
        publicKey: registrationOptionsToCreationOptions(options),
      })) as PublicKeyCredential | null;
      if (!credential) {
        setError('The passkey ceremony was cancelled — nothing was enrolled.');
        return;
      }
      await withStepUpRetry(
        () => finishPasskeyRegistration(serializeRegistrationCredential(credential), name),
        p,
      );
      setPassword('');
      setCode('');
      setName('');
      setPasskeys(await listPasskeys());
      setDone('Passkey added. It now works as a second factor at login.');
    } catch (err) {
      setError(describePasskeyError(err));
    } finally {
      setPending(false);
    }
  }

  async function onDelete(id: number) {
    if (!requireProof()) return;
    setPending(true);
    setError(null);
    setDone(null);
    try {
      // Removing a second factor is as sensitive as adding one — the
      // same freshness gate and the same transparent re-proof.
      await withStepUpRetry(() => deletePasskey(id), proof());
      setPassword('');
      setCode('');
      setPasskeys(await listPasskeys());
      setDone('Passkey removed.');
    } catch (err) {
      setError(describePasskeyError(err));
    } finally {
      setPending(false);
    }
  }

  async function onRename(id: number) {
    if (!requireProof()) return;
    setPending(true);
    setError(null);
    setDone(null);
    try {
      // Renaming is display-only, but it is a passkey mutation — the
      // same freshness gate as add/remove, so a stale token cannot
      // relabel keys into a deletion mistake (backlog #241).
      await withStepUpRetry(() => renamePasskey(id, renameValue), proof());
      setPassword('');
      setCode('');
      setRenamingId(null);
      setRenameValue('');
      setPasskeys(await listPasskeys());
      setDone('Passkey renamed.');
    } catch (err) {
      setError(describePasskeyError(err));
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="mt-4 border-t border-neutral-200 pt-3">
      <h3 className="mb-1 text-sm font-semibold">Passkeys</h3>
      <p className="mb-3 text-sm text-neutral-500">
        A passkey on this device works as a second factor at login — an alternative to the
        authenticator code, never a password replacement.
      </p>
      {error && (
        <p role="alert" className="mb-3 rounded bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}
      {done && (
        <p role="status" className="mb-3 rounded bg-green-50 px-3 py-2 text-sm text-green-800">
          {done}
        </p>
      )}
      {passkeys !== null && passkeys.length > 0 && (
        <ul className="mb-3 space-y-2">
          {passkeys.map((pk) => (
            <li
              key={pk.id}
              className="flex items-center justify-between gap-2 rounded border border-neutral-200 px-3 py-2 text-sm"
            >
              {renamingId === pk.id ? (
                <>
                  <input
                    aria-label={`New name for ${displayPasskeyName(pk)}`}
                    type="text"
                    value={renameValue}
                    onChange={(e) => setRenameValue(e.target.value)}
                    placeholder="Leave blank for the date label"
                    className="min-w-0 flex-1 rounded border border-neutral-300 px-2 py-1 text-sm"
                  />
                  <button
                    type="button"
                    onClick={() => onRename(pk.id)}
                    disabled={pending}
                    className="rounded border border-neutral-300 px-2 py-1 text-xs font-medium hover:bg-neutral-50 disabled:opacity-50"
                  >
                    Save
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setRenamingId(null);
                      setRenameValue('');
                    }}
                    disabled={pending}
                    className="rounded border border-neutral-300 px-2 py-1 text-xs font-medium hover:bg-neutral-50 disabled:opacity-50"
                  >
                    Cancel
                  </button>
                </>
              ) : (
                <>
                  <span className="min-w-0 flex-1 text-neutral-700">
                    {displayPasskeyName(pk)}
                  </span>
                  <button
                    type="button"
                    onClick={() => {
                      setRenamingId(pk.id);
                      setRenameValue(pk.name ?? '');
                    }}
                    disabled={pending}
                    aria-label={`Rename ${displayPasskeyName(pk)}`}
                    className="rounded border border-neutral-300 px-2 py-1 text-xs font-medium hover:bg-neutral-50 disabled:opacity-50"
                  >
                    Rename
                  </button>
                  <button
                    type="button"
                    onClick={() => onDelete(pk.id)}
                    disabled={pending}
                    aria-label={`Remove ${displayPasskeyName(pk)}`}
                    className="rounded border border-neutral-300 px-2 py-1 text-xs font-medium text-red-700 hover:bg-red-50 disabled:opacity-50"
                  >
                    Remove
                  </button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      <form onSubmit={onAdd} className="space-y-3">
        <div>
          <label htmlFor="settings-passkey-password" className="mb-1 block text-sm font-medium">
            Current password
          </label>
          <input
            id="settings-passkey-password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </div>
        {totpOn && (
          <div>
            <label htmlFor="settings-passkey-code" className="mb-1 block text-sm font-medium">
              Authenticator code
            </label>
            <input
              id="settings-passkey-code"
              type="text"
              autoComplete="one-time-code"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
            />
          </div>
        )}
        <div>
          <label htmlFor="settings-passkey-name" className="mb-1 block text-sm font-medium">
            Name this passkey (optional)
          </label>
          <input
            id="settings-passkey-name"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g. My phone — blank keeps the date label"
            className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </div>
        <button
          type="submit"
          disabled={pending}
          className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {pending ? 'Adding…' : 'Add a passkey on this device'}
        </button>
      </form>
    </div>
  );
}
