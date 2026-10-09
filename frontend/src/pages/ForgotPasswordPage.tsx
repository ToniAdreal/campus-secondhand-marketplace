import axios from 'axios';
import { type FormEvent, useState } from 'react';
import { Link } from 'react-router-dom';
import { requestPasswordReset } from '../api/auth';

/** Reads the human message out of the backend {code,message,data} envelope. */
function envelopeMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const message = (err.response?.data as { message?: string } | undefined)?.message;
    if (message) return message;
    if (!err.response) return 'Network error — is the backend running?';
  }
  return 'Password reset request failed';
}

/**
 * Forgot password (backlog #99). Posts the identifier to
 * POST /api/auth/password-reset, which always answers the identical 200
 * envelope whether or not the account exists — so this page renders the
 * exact same success state for every identifier and never claims an
 * email was sent: the backend's default mail sender is log-only and
 * delivers nothing in this demo.
 */
export default function ForgotPasswordPage() {
  const [usernameOrEmail, setUsernameOrEmail] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);
  const [pending, setPending] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (usernameOrEmail.trim() === '') {
      setError('Username or email is required.');
      return;
    }
    setPending(true);
    setError(null);
    try {
      await requestPasswordReset({ usernameOrEmail: usernameOrEmail.trim() });
      setSubmitted(true);
    } catch (err) {
      setError(envelopeMessage(err));
    } finally {
      setPending(false);
    }
  }

  if (submitted) {
    return (
      <div className="mx-auto max-w-sm rounded-lg bg-white p-6 shadow-sm">
        <h1 className="mb-4 text-xl font-bold">Check for your reset link</h1>
        <p role="status" className="rounded bg-green-50 px-3 py-2 text-sm text-green-800">
          If an account exists for that username or email, a reset link was issued. In this
          demo the default mail sender is log-only, so no email is actually delivered.
        </p>
        <p className="mt-4 text-sm text-neutral-500">
          <Link to="/login" className="text-blue-600 hover:underline">
            Back to login
          </Link>
        </p>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-sm rounded-lg bg-white p-6 shadow-sm">
      <h1 className="mb-4 text-xl font-bold">Forgot password</h1>
      {error && (
        <p role="alert" className="mb-4 rounded bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}
      <form onSubmit={onSubmit} className="space-y-3">
        <div>
          <label htmlFor="forgot-identifier" className="mb-1 block text-sm font-medium">
            Username or email
          </label>
          <input
            id="forgot-identifier"
            type="text"
            autoComplete="username"
            value={usernameOrEmail}
            onChange={(e) => setUsernameOrEmail(e.target.value)}
            className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </div>
        <button
          type="submit"
          disabled={pending}
          className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {pending ? 'Requesting…' : 'Request reset link'}
        </button>
      </form>
      <p className="mt-4 text-sm text-neutral-500">
        Remembered it?{' '}
        <Link to="/login" className="text-blue-600 hover:underline">
          Back to login
        </Link>
      </p>
    </div>
  );
}
