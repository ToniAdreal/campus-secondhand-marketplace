import { type FormEvent, useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  confirmEmailChange,
  confirmEmailVerification,
  resendEmailVerification,
} from '../api/auth';
import { throttleWaitCopy } from './authThrottle';

type ConfirmState = 'confirming' | 'confirmed' | 'failed';

/**
 * Verify email (backlog #138): /verify-email?token=… — the token from the
 * verification link is the credential for POST
 * /api/auth/email-verification/confirm, so arriving on this page IS the
 * confirmation: the call fires on mount (guarded to once — the token is
 * single-use, and a StrictMode double-effect or a remount must not replay
 * it into a spurious failure state). A missing token param renders an
 * explanatory state and makes NO API call. Unknown, used and expired
 * tokens all fail with the backend's identical 400, mapped to ONE generic
 * message — the page must not distinguish them. Success grants no session
 * by itself; the page points at /login, where the password completes the
 * sign-in.
 *
 * Backlog #230: the failed state also offers its own resend control — an
 * email field + button reusing the #138 resend client — instead of only
 * pointing at the register page, since an expired link lands here, not
 * there. The resend endpoint's identical-200 contract means the success
 * copy is the same "check your inbox" confirmation RegisterPage renders,
 * whatever the address's state; a failure maps to one generic error copy
 * and never to an account-state hint.
 * Backlog #265: the resend endpoint shares the per-IP verification
 * bucket, so a 429 carrying Retry-After renders the wait copy instead of
 * that generic copy; a 429 without a usable header keeps the generic copy.
 */
export default function VerifyEmailPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');
  const [state, setState] = useState<ConfirmState>('confirming');
  const firedRef = useRef(false);
  const [email, setEmail] = useState('');
  const [resending, setResending] = useState(false);
  const [resent, setResent] = useState(false);
  const [resendError, setResendError] = useState<string | null>(null);

  async function onResend(e: FormEvent) {
    e.preventDefault();
    const address = email.trim();
    if (address === '' || resending) return;
    setResending(true);
    setResendError(null);
    try {
      await resendEmailVerification({ email: address });
      setResent(true);
    } catch (err) {
      setResendError(
        throttleWaitCopy(err) ?? 'Could not resend the verification link',
      );
    } finally {
      setResending(false);
    }
  }

  useEffect(() => {
    if (!token || firedRef.current) {
      return;
    }
    firedRef.current = true;
    let cancelled = false;
    // Backlog #208: the email-change confirm link lands on this same
    // route shape, so a token the verification confirm rejects is
    // tried against the email-change confirm — the two token tables
    // are disjoint and a rejected verification confirm consumes
    // nothing on the change side. Failure renders only when BOTH
    // flows reject the token.
    // Backlog #251: the confirm chain is also aborted on unmount —
    // the old `cancelled` flag only suppressed the setState, the
    // request itself kept running after the route changed. An abort
    // never falls through to the second confirm: the token was not
    // rejected, the page simply left.
    const controller = new AbortController();
    confirmEmailVerification({ token }, controller.signal)
      .catch((err: unknown) => {
        if (controller.signal.aborted) throw err;
        return confirmEmailChange({ token }, controller.signal);
      })
      .then(() => {
        if (!cancelled) setState('confirmed');
      })
      .catch(() => {
        if (!cancelled) setState('failed');
      });
    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [token]);

  if (!token) {
    return (
      <div className="mx-auto max-w-sm rounded-lg bg-white p-6 shadow-sm">
        <h1 className="mb-4 text-xl font-bold">Verify email</h1>
        <p role="alert" className="rounded bg-red-50 px-3 py-2 text-sm text-red-700">
          This verification link is missing its token, so there is nothing to confirm.
          Register again or request a fresh link from the register page.
        </p>
        <p className="mt-4 text-sm text-neutral-500">
          <Link to="/register" className="text-blue-600 hover:underline">
            Back to register
          </Link>
        </p>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-sm rounded-lg bg-white p-6 shadow-sm">
      <h1 className="mb-4 text-xl font-bold">Verify email</h1>
      {state === 'confirming' && (
        <p role="status" className="text-sm text-neutral-600">
          Confirming your email address…
        </p>
      )}
      {state === 'confirmed' && (
        <>
          <p role="status" className="rounded bg-green-50 px-3 py-2 text-sm text-green-800">
            Email address confirmed. You can now log in with your password.
          </p>
          <p className="mt-4 text-sm text-neutral-500">
            <Link to="/login" className="text-blue-600 hover:underline">
              Back to login
            </Link>
          </p>
        </>
      )}
      {state === 'failed' && (
        <>
          <p role="alert" className="rounded bg-red-50 px-3 py-2 text-sm text-red-700">
            This verification link is invalid or has expired. A fresh link replaces it —
            earlier links stop working.
          </p>
          {resent ? (
            <p role="status" className="mt-4 rounded bg-green-50 px-3 py-2 text-sm text-green-800">
              Check your inbox — a fresh verification link was issued. Earlier links no
              longer work.
            </p>
          ) : (
            <form onSubmit={onResend} className="mt-4 space-y-3">
              <div>
                <label
                  htmlFor="verify-email-resend-email"
                  className="mb-1 block text-sm font-medium"
                >
                  Email
                </label>
                <input
                  id="verify-email-resend-email"
                  type="email"
                  autoComplete="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
                />
              </div>
              {resendError && (
                <p role="alert" className="rounded bg-red-50 px-3 py-2 text-sm text-red-700">
                  {resendError}
                </p>
              )}
              <button
                type="submit"
                disabled={resending || email.trim() === ''}
                className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                {resending ? 'Resending…' : 'Resend verification link'}
              </button>
            </form>
          )}
          <p className="mt-4 text-sm text-neutral-500">
            <Link to="/register" className="text-blue-600 hover:underline">
              Back to register
            </Link>
          </p>
        </>
      )}
    </div>
  );
}
