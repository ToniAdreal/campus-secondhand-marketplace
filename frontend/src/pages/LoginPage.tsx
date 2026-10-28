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
    <div className="mx-auto mt-6 max-w-md rounded-2xl border border-neutral-200/80 bg-white p-8 shadow-xl shadow-blue-100/60 sm:mt-10">
      <h1 className="mb-5 text-2xl font-extrabold tracking-tight text-neutral-900">Login</h1>
      {passwordResetDone && !challenge && (
        <p role="status" className="mb-4 rounded-lg border border-green-200 bg-green-50 px-3.5 py-2.5 text-sm text-green-800">
          Password reset complete. You have been signed out everywhere — log in with your
          new password.
        </p>
      )}
      {error && (
        <p role="alert" className="mb-4 rounded-lg border border-red-200 bg-red-50 px-3.5 py-2.5 text-sm text-red-700">
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
                <label htmlFor="login-totp-code" className="mb-1.5 block text-sm font-semibold text-neutral-700">
                  Two-factor code
                </label>
                <input
                  id="login-totp-code"
                  type="text"
                  autoComplete="one-time-code"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  className="w-full rounded-lg border border-neutral-300 bg-neutral-50/60 px-3.5 py-2.5 text-sm shadow-inner outline-none transition focus:border-blue-500 focus:bg-white focus:ring-4 focus:ring-blue-500/15"
                />
              </div>
            </>
          )}
          {factors.includes('passkey') && webauthnSupported() && (
            <button
              type="button"
              onClick={onUsePasskey}
              disabled={pending}
              className="w-full rounded-lg border border-neutral-300 px-3 py-2.5 text-sm font-semibold text-neutral-700 transition hover:bg-neutral-50 disabled:opacity-50"
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
              className="w-full rounded-lg bg-blue-600 px-3 py-2.5 text-sm font-semibold text-white shadow-md shadow-blue-600/25 transition hover:bg-blue-700 active:scale-[0.99] disabled:opacity-50"
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
              <label htmlFor="login-identifier" className="mb-1.5 block text-sm font-semibold text-neutral-700">
                Username or email
              </label>
              <input
                id="login-identifier"
                type="text"
                autoComplete="username"
                value={usernameOrEmail}
                onChange={(e) => setUsernameOrEmail(e.target.value)}
                className="w-full rounded-lg border border-neutral-300 bg-neutral-50/60 px-3.5 py-2.5 text-sm shadow-inner outline-none transition focus:border-blue-500 focus:bg-white focus:ring-4 focus:ring-blue-500/15"
              />
            </div>
            <div>
              <label htmlFor="login-password" className="mb-1.5 block text-sm font-semibold text-neutral-700">
                Password
              </label>
              <input
                id="login-password"
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full rounded-lg border border-neutral-300 bg-neutral-50/60 px-3.5 py-2.5 text-sm shadow-inner outline-none transition focus:border-blue-500 focus:bg-white focus:ring-4 focus:ring-blue-500/15"
              />
            </div>
            <button
              type="submit"
              disabled={pending}
              className="w-full rounded-lg bg-blue-600 px-3 py-2.5 text-sm font-semibold text-white shadow-md shadow-blue-600/25 transition hover:bg-blue-700 active:scale-[0.99] disabled:opacity-50"
            >
              {pending ? 'Logging in…' : 'Login'}
            </button>
          </form>
          <p className="mt-4 text-sm text-neutral-500">
            <Link to="/forgot-password" className="text-blue-600 hover:underline">
              Forgot password?
            </Link>
          </p>
        </>
      )}
    </div>
  );
}
