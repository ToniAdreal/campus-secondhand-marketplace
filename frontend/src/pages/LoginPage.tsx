import axios from 'axios';
import { type FormEvent, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { authenticateTotp, login, startPasskeyAssertion } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';
import {
  assertionOptionsToRequestOptions,
  serializeAssertionCredential,
  webauthnSupported,
} from '../api/webauthn';
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
 * Login (backlog #17) + the second-factor step (backlog #100). A plain
 * login finishes in one POST; on a second-factor account the backend
 * answers 202 with a signed challenge instead of a token pair, and this
 * page switches to the factor step: the code field accepts a 6-digit
 * authenticator code OR a one-time recovery code (the backend's
 * single-field contract), and — when the account holds a passkey and the
 * browser supports WebAuthn (backlog #220) — a "Use a passkey" button
 * drives `navigator.credentials.get()` and exchanges the assertion at
 * the same /api/auth/2fa/authenticate endpoint. A wrong proof gets the
 * backend's identical 401 — the page shows it verbatim and never hints
 * which factor or which check failed.
 */
export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const user = useAuthStore((s) => s.user);
  const storeLogin = useAuthStore((s) => s.login);

  const [usernameOrEmail, setUsernameOrEmail] = useState('');
  const [password, setPassword] = useState('');
  const [challenge, setChallenge] = useState<string | null>(null);
  // The factor names the 202 challenge accepts ("totp" and/or "passkey").
  const [factors, setFactors] = useState<string[]>([]);
  const [code, setCode] = useState('');
  const [rememberDevice, setRememberDevice] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  if (user) {
    // Already signed in — no reason to show the form.
    return <Navigate to="/" replace />;
  }

  function finishLogin(loggedIn: Parameters<typeof storeLogin>[0], accessToken: string) {
    storeLogin(loggedIn, accessToken);
    const from = (location.state as { from?: string } | null)?.from ?? '/';
    navigate(from, { replace: true });
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (usernameOrEmail.trim() === '' || password === '') {
      setError('Username or email and password are required.');
      return;
    }
    setPending(true);
    setError(null);
    try {
      const outcome = await login({
        usernameOrEmail: usernameOrEmail.trim(),
        password,
      });
      if (outcome.kind === 'totp-challenge') {
        // No token pair yet — the password is spent; only the challenge
        // (held in memory, never persisted) carries into the factor step.
        setChallenge(outcome.challenge);
        // Backlog #220: the 202 advertises which proofs can succeed — the
        // page renders exactly those options.
        setFactors(outcome.factors);
        setPassword('');
        setCode('');
        setRememberDevice(false);
        return;
      }
      finishLogin(outcome.user, outcome.accessToken);
    } catch (err) {
      setError(envelopeMessage(err, 'Login failed'));
    } finally {
      setPending(false);
    }
  }

  async function onCodeSubmit(e: FormEvent) {
    e.preventDefault();
    if (!challenge) return;
    if (code.trim() === '') {
      setError('Enter your two-factor code or a recovery code.');
      return;
    }
    setPending(true);
    setError(null);
    try {
      const { user: loggedIn, accessToken } = await authenticateTotp({
        challenge,
        code: code.trim(),
        // Backlog #199: the opt-in is honoured only on a successful
        // exchange — a failed one creates no trust server-side.
        rememberDevice,
      });
      finishLogin(loggedIn, accessToken);
    } catch (err) {
      // Wrong TOTP code, wrong recovery code and expired challenge all
      // land on the backend's identical 401 — shown verbatim.
      setError(envelopeMessage(err, 'Two-factor authentication failed'));
    } finally {
      setPending(false);
    }
  }

  async function onUsePasskey() {
    if (!challenge) return;
    setPending(true);
    setError(null);
    try {
      // Backlog #220: the 202 challenge opens a single-use assertion
      // ceremony; the browser signs it and the same exchange endpoint
      // finishes the login.
      const options = await startPasskeyAssertion(challenge);
      const credential = (await navigator.credentials.get({
        publicKey: assertionOptionsToRequestOptions(options),
      })) as PublicKeyCredential | null;
      if (!credential) {
        setError('The passkey ceremony was cancelled.');
        return;
      }
      const { user: loggedIn, accessToken } = await authenticateTotp({
        challenge,
        code: '',
        assertion: serializeAssertionCredential(credential),
        rememberDevice,
      });
      finishLogin(loggedIn, accessToken);
    } catch (err) {
      // A cancelled ceremony, an unknown credential and a failed
      // ceremony all land on the backend's identical 401 — shown
      // verbatim, never a hint about which check failed.
      setError(envelopeMessage(err, 'Passkey sign-in failed'));
    } finally {
      setPending(false);
    }
  }

  const passwordResetDone =
    (location.state as { passwordReset?: boolean } | null)?.passwordReset === true;

  return (
    <div className="mx-auto mt-6 max-w-md rounded-[20px] border border-apple-hairline bg-white p-6 sm:mt-10 sm:p-8">
      <div aria-hidden="true" className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-[14px] bg-apple-ink text-white">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-6 w-6">
          <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 10.5V6a3.75 3.75 0 10-7.5 0v4.5m11.356-1.993l1.263 12c.07.665-.45 1.243-1.119 1.243H4.25a1.125 1.125 0 01-1.12-1.243l1.264-12A1.125 1.125 0 015.513 7.5h12.974c.576 0 1.059.435 1.119 1.007z" />
        </svg>
      </div>
      <h1 className="mb-2 text-center text-2xl font-bold tracking-tight text-apple-ink">Login</h1>
      <p className="mb-6 text-center text-sm text-apple-muted">Sign in to buy and sell on campus</p>
      {passwordResetDone && !challenge && (
        <p role="status" className="mb-4 rounded-[10px] border border-green-200 bg-green-50 px-3.5 py-2.5 text-sm text-green-800">
          Password reset complete. You have been signed out everywhere — log in with your
          new password.
        </p>
      )}
      {error && (
        <p role="alert" className="mb-4 rounded-[10px] border border-red-200 bg-red-50 px-3.5 py-2.5 text-sm text-red-700">
          {error}
        </p>
      )}
      {challenge ? (
        <form onSubmit={onCodeSubmit} className="space-y-4">
          {factors.includes('totp') && (
            <>
              <p className="text-sm text-neutral-600">
                Enter the 6-digit code from your authenticator app — or one of your recovery
                codes if you no longer have the app.
              </p>
              <div>
                <label htmlFor="login-totp-code" className="mb-1 block text-sm font-semibold text-apple-softink">
                  Two-factor code
                </label>
                <input
                  id="login-totp-code"
                  type="text"
                  autoComplete="one-time-code"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  className="w-full rounded-[10px] border border-apple-hairline bg-white px-3.5 py-3 text-[17px] outline-none transition focus:border-apple-focus"
                />
              </div>
            </>
          )}
          {factors.includes('passkey') && webauthnSupported() && (
            <button
              type="button"
              onClick={onUsePasskey}
              disabled={pending}
              className="w-full rounded-full border border-apple-blue bg-white px-3 py-3 text-[15px] font-semibold text-apple-blue transition hover:bg-apple-parchment disabled:opacity-50"
            >
              {pending ? 'Waiting for your passkey…' : 'Use a passkey instead'}
            </button>
          )}
          <div>
            <label htmlFor="login-remember-device" className="flex items-start gap-2 text-sm">
              <input
                id="login-remember-device"
                type="checkbox"
                checked={rememberDevice}
                onChange={(e) => setRememberDevice(e.target.checked)}
                className="mt-0.5"
              />
              <span>
                Trust this device — skip the code step on this device for 30 days. Your
                password is still required at every login, and you can revoke the trust
                at any time from My sessions.
              </span>
            </label>
          </div>
          {factors.includes('totp') && (
            <button
              type="submit"
              disabled={pending}
              className="w-full rounded-full bg-apple-blue px-3 py-3 text-[17px] font-semibold text-white transition hover:bg-apple-focus active:scale-[0.99] disabled:opacity-50"
            >
              {pending ? 'Verifying…' : 'Verify code'}
            </button>
          )}
          <button
            type="button"
            onClick={() => {
              setChallenge(null);
              setFactors([]);
              setCode('');
              setRememberDevice(false);
              setError(null);
            }}
            className="w-full rounded px-3 py-2 text-sm text-neutral-600 hover:underline"
          >
            Back to login
          </button>
        </form>
      ) : (
        <>
          <form onSubmit={onSubmit} className="space-y-4">
            <div>
              <label htmlFor="login-identifier" className="mb-1 block text-sm font-semibold text-apple-softink">
                Username or email
              </label>
              <div className="relative">
                <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="pointer-events-none absolute left-3.5 top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-neutral-400">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 6a3.75 3.75 0 11-7.5 0 3.75 3.75 0 017.5 0zM4.501 20.118a7.5 7.5 0 0114.998 0A17.933 17.933 0 0112 21.75c-2.676 0-5.216-.584-7.499-1.632z" />
                </svg>
                <input
                  id="login-identifier"
                  type="text"
                  autoComplete="username"
                  value={usernameOrEmail}
                  onChange={(e) => setUsernameOrEmail(e.target.value)}
                  className="w-full rounded-[10px] border border-apple-hairline bg-white py-3 pl-11 pr-3.5 text-[17px] outline-none transition focus:border-apple-focus"
                />
              </div>
            </div>
            <div>
              <label htmlFor="login-password" className="mb-1 block text-sm font-semibold text-apple-softink">
                Password
              </label>
              <div className="relative">
                <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="pointer-events-none absolute left-3.5 top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-neutral-400">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M16.5 10.5V6.75a4.5 4.5 0 10-9 0v3.75m-.75 11.25h10.5a2.25 2.25 0 002.25-2.25v-6.75a2.25 2.25 0 00-2.25-2.25H6.75a2.25 2.25 0 00-2.25 2.25v6.75a2.25 2.25 0 002.25 2.25z" />
                </svg>
                <input
                  id="login-password"
                  type="password"
                  autoComplete="current-password"
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
              {pending ? 'Logging in…' : 'Login'}
            </button>
          </form>
          <p className="mt-4 text-center text-sm">
            <Link to="/forgot-password" className="text-apple-blue hover:underline">
              Forgot password?
            </Link>
          </p>
          <p className="mt-6 border-t border-apple-soft pt-5 text-center text-sm text-apple-muted">
            New to Campus Marketplace?{' '}
            <Link to="/register" className="font-semibold text-blue-600 hover:underline">
              Create an account
            </Link>
          </p>
        </>
      )}
    </div>
  );
}
