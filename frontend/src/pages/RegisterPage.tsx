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
        <div aria-hidden="true" className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-[14px] bg-apple-ink text-white">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-6 w-6">
          <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 10.5V6a3.75 3.75 0 10-7.5 0v4.5m11.356-1.993l1.263 12c.07.665-.45 1.243-1.119 1.243H4.25a1.125 1.125 0 01-1.12-1.243l1.264-12A1.125 1.125 0 015.513 7.5h12.974c.576 0 1.059.435 1.119 1.007z" />
        </svg>
      </div>
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
      <div aria-hidden="true" className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-[14px] bg-apple-ink text-white">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-6 w-6">
          <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 10.5V6a3.75 3.75 0 10-7.5 0v4.5m11.356-1.993l1.263 12c.07.665-.45 1.243-1.119 1.243H4.25a1.125 1.125 0 01-1.12-1.243l1.264-12A1.125 1.125 0 015.513 7.5h12.974c.576 0 1.059.435 1.119 1.007z" />
        </svg>
      </div>
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
            <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="pointer-events-none absolute left-3.5 top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-neutral-400">
              <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 6a3.75 3.75 0 11-7.5 0 3.75 3.75 0 017.5 0zM4.501 20.118a7.5 7.5 0 0114.998 0A17.933 17.933 0 0112 21.75c-2.676 0-5.216-.584-7.499-1.632z" />
            </svg>
            <input
              id="register-username"
              type="text"
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="w-full rounded-[10px] border border-apple-hairline bg-white py-3 pl-11 pr-3.5 text-[17px] outline-none transition focus:border-apple-focus"
            />
          </div>
        </div>
        <div>
          <label htmlFor="register-email" className="mb-1 block text-sm font-semibold text-apple-softink">
            Email
          </label>
          <div className="relative">
            <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="pointer-events-none absolute left-3.5 top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-neutral-400">
              <path strokeLinecap="round" strokeLinejoin="round" d="M21.75 6.75v10.5a2.25 2.25 0 01-2.25 2.25h-15a2.25 2.25 0 01-2.25-2.25V6.75m19.5 0A2.25 2.25 0 0019.5 4.5h-15a2.25 2.25 0 00-2.25 2.25m19.5 0v.243a2.25 2.25 0 01-1.07 1.916l-7.5 4.615a2.25 2.25 0 01-2.36 0L3.32 8.91a2.25 2.25 0 01-1.07-1.916V6.75" />
            </svg>
            <input
              id="register-email"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full rounded-[10px] border border-apple-hairline bg-white py-3 pl-11 pr-3.5 text-[17px] outline-none transition focus:border-apple-focus"
            />
          </div>
        </div>
        <div>
          <label htmlFor="register-password" className="mb-1 block text-sm font-semibold text-apple-softink">
            Password
          </label>
          <div className="relative">
            <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="pointer-events-none absolute left-3.5 top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-neutral-400">
              <path strokeLinecap="round" strokeLinejoin="round" d="M16.5 10.5V6.75a4.5 4.5 0 10-9 0v3.75m-.75 11.25h10.5a2.25 2.25 0 002.25-2.25v-6.75a2.25 2.25 0 00-2.25-2.25H6.75a2.25 2.25 0 00-2.25 2.25v6.75a2.25 2.25 0 002.25 2.25z" />
            </svg>
            <input
              id="register-password"
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full rounded-[10px] border border-apple-hairline bg-white py-3 pl-11 pr-3.5 text-[17px] outline-none transition focus:border-apple-focus"
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
