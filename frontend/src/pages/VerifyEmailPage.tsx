import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { confirmEmailChange, confirmEmailVerification } from '../api/auth';

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
 */
export default function VerifyEmailPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');
  const [state, setState] = useState<ConfirmState>('confirming');
  const firedRef = useRef(false);

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
    confirmEmailVerification({ token })
      .catch(() => confirmEmailChange({ token }))
      .then(() => {
        if (!cancelled) setState('confirmed');
      })
      .catch(() => {
        if (!cancelled) setState('failed');
      });
    return () => {
      cancelled = true;
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
        <p role="alert" className="rounded bg-red-50 px-3 py-2 text-sm text-red-700">
          This verification link is invalid or has expired. Register page resends issue a
          fresh link — earlier links stop working.
        </p>
      )}
    </div>
  );
}
