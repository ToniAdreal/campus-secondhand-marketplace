import axios from 'axios';
import { type FormEvent, useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { register, resendEmailVerification } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';
import { throttleWaitCopy } from './authThrottle';

/**
 * Reads the human message out of the backend {code,message,data} envelope.
 * Backlog #242: a 429 carrying Retry-After renders the wait copy instead,
 * and a 429 without a usable header falls back to the generic copy — with
 * no number there is no wait to show. Every other status keeps the
 * previous mapping untouched.
 */
function envelopeMessage(err: unknown, fallback: string): string {
  const waitCopy = throttleWaitCopy(err);
  if (waitCopy) return waitCopy;
  if (axios.isAxiosError(err)) {
    if (err.response?.status === 429) return fallback;
    const message = (err.response?.data as { message?: string } | undefined)?.message;
    if (message) return message;
    if (!err.response) return 'Network error — is the backend running?';
  }
  return fallback;
}

/**
 * Register (backlog #138). Until #138 the SPA had no registration page at
 * all (accounts were created through the API only). With the backend's
 * email-verification gate on, a successful POST /api/auth/register
 * answers 201 with NO session: the page switches to a "check your inbox"
 * state — the account cannot log in until the mailed token is confirmed
 * at /verify-email. The copy is honest about the demo's mail seam: the
 * default sender is log-only, so no email is actually delivered. The
 * resend button posts to /email-verification/resend, whose identical-200
 * contract means the resent note renders the same whatever the address's
 * state. With the gate off the register call returns a session and the
 * page signs the user in, exactly like a login.
 */
export default function RegisterPage() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const storeLogin = useAuthStore((s) => s.login);

  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [awaitingVerification, setAwaitingVerification] = useState<string | null>(null);
  const [resent, setResent] = useState(false);
  const [resending, setResending] = useState(false);

  if (user) {
    // Already signed in — no reason to show the form.
    return <Navigate to="/" replace />;
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (username.trim() === '' || email.trim() === '' || password === '') {
      setError('Username, email and password are all required.');
      return;
    }
    setPending(true);
    setError(null);
    try {
      const outcome = await register({
        username: username.trim(),
        email: email.trim(),
        password,
      });
      if (outcome.kind === 'verification-required') {
        setAwaitingVerification(email.trim());
        setPassword('');
        return;
      }
      storeLogin(outcome.user, outcome.accessToken);
      navigate('/', { replace: true });
    } catch (err) {
      setError(envelopeMessage(err, 'Registration failed'));
    } finally {
      setPending(false);
    }
  }

  async function onResend() {
    if (!awaitingVerification) return;
    setResending(true);
    setError(null);
    try {
      await resendEmailVerification({ email: awaitingVerification });
      setResent(true);
    } catch (err) {
      setError(envelopeMessage(err, 'Could not resend the verification link'));
    } finally {
      setResending(false);
    }
  }

  if (awaitingVerification) {
    return (
      <div className="mx-auto mt-6 max-w-md rounded-[20px] border border-apple-hairline bg-white p-6 sm:mt-10 sm:p-8">
      <h1 className="mb-5 text-center text-2xl font-bold tracking-tight text-apple-ink">Check your inbox</h1>
        <p role="status" className="rounded-[10px] border border-green-200 bg-green-50 px-3.5 py-2.5 text-sm text-green-800">
          Account created for {awaitingVerification}. Confirm your email address with the
          verification link before logging in — no session has been started yet. In this
          demo the default mail sender is log-only, so no email is actually delivered.
        </p>
        {error && (
          <p role="alert" className="mt-4 rounded-[10px] border border-red-200 bg-red-50 px-3.5 py-2.5 text-sm text-red-700">
            {error}
          </p>
        )}
        {resent ? (
          <p role="status" className="mt-4 text-sm text-neutral-600">
            A fresh verification link was issued — earlier links no longer work.
          </p>
        ) : (
          <button
            type="button"
            onClick={onResend}
            disabled={resending}
            className="mt-4 w-full rounded-full bg-apple-blue px-3 py-3 text-[17px] font-semibold text-white transition hover:bg-apple-focus active:scale-[0.99] disabled:opacity-50"
          >
            {resending ? 'Resending…' : 'Resend verification link'}
          </button>
        )}
        <p className="mt-6 border-t border-apple-soft pt-5 text-center text-sm text-apple-muted">
          Already confirmed?{' '}
          <Link to="/login" className="font-semibold text-blue-600 hover:underline">
            Back to login
          </Link>
        </p>
      </div>
    );
  }

  return (
    <div className="mx-auto mt-6 max-w-md rounded-[20px] border border-apple-hairline bg-white p-6 sm:mt-10 sm:p-8">
      <h1 className="mb-2 text-center text-2xl font-bold tracking-tight text-apple-ink">Register</h1>
      <p className="mb-6 text-center text-sm text-apple-muted">Join Campus Marketplace to buy and sell on campus</p>
      {error && (
        <p role="alert" className="mb-4 rounded-[10px] border border-red-200 bg-red-50 px-3.5 py-2.5 text-sm text-red-700">
          {error}
        </p>
      )}
      <form onSubmit={onSubmit} className="space-y-4">
        <div>
          <label htmlFor="register-username" className="mb-1 block text-sm font-semibold text-apple-softink">
            Username
          </label>
          <div className="relative">
            <input
              id="register-username"
              type="text"
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="w-full rounded-[10px] border border-apple-hairline bg-white px-3.5 py-3 text-[17px] outline-none transition focus:border-apple-focus"
            />
          </div>
        </div>
        <div>
          <label htmlFor="register-email" className="mb-1 block text-sm font-semibold text-apple-softink">
            Email
          </label>
          <div className="relative">
            <input
              id="register-email"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full rounded-[10px] border border-apple-hairline bg-white px-3.5 py-3 text-[17px] outline-none transition focus:border-apple-focus"
            />
          </div>
        </div>
        <div>
          <label htmlFor="register-password" className="mb-1 block text-sm font-semibold text-apple-softink">
            Password
          </label>
          <div className="relative">
            <input
              id="register-password"
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full rounded-[10px] border border-apple-hairline bg-white px-3.5 py-3 text-[17px] outline-none transition focus:border-apple-focus"
            />
          </div>
        </div>
        <button
          type="submit"
          disabled={pending}
          className="w-full rounded-full bg-apple-blue px-3 py-3 text-[17px] font-semibold text-white transition hover:bg-apple-focus active:scale-[0.99] disabled:opacity-50"
        >
          {pending ? 'Creating account…' : 'Create account'}
        </button>
      </form>
      <p className="mt-6 border-t border-apple-soft pt-5 text-center text-sm text-apple-muted">
        Already have an account?{' '}
        <Link to="/login" className="font-semibold text-blue-600 hover:underline">
          Back to login
        </Link>
      </p>
    </div>
  );
}
