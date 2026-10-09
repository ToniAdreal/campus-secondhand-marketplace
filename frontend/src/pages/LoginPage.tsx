import axios from 'axios';
import { type FormEvent, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { authenticateTotp, login } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';

/** Reads the human message out of the backend {code,message,data} envelope. */
function envelopeMessage(err: unknown, fallback: string): string {
  if (axios.isAxiosError(err)) {
    const message = (err.response?.data as { message?: string } | undefined)?.message;
    if (message) return message;
    if (!err.response) return 'Network error — is the backend running?';
  }
  return fallback;
}

/**
 * Login (backlog #17) + the TOTP second-factor step (backlog #100). A
 * plain login finishes in one POST; on a 2FA-enabled account the backend
 * answers 202 with a signed challenge instead of a token pair, and this
 * page switches to a code step: the same field accepts a 6-digit
 * authenticator code OR a one-time recovery code (the backend's
 * single-field contract). A wrong code gets the backend's identical 401
 * copy — the page shows it verbatim and never hints which form failed.
 */
export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const user = useAuthStore((s) => s.user);
  const storeLogin = useAuthStore((s) => s.login);

  const [usernameOrEmail, setUsernameOrEmail] = useState('');
  const [password, setPassword] = useState('');
  const [challenge, setChallenge] = useState<string | null>(null);
  const [code, setCode] = useState('');
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
        // (held in memory, never persisted) carries into the code step.
        setChallenge(outcome.challenge);
        setPassword('');
        setCode('');
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

  const passwordResetDone =
    (location.state as { passwordReset?: boolean } | null)?.passwordReset === true;

  return (
    <div className="mx-auto max-w-sm rounded-lg bg-white p-6 shadow-sm">
      <h1 className="mb-4 text-xl font-bold">Login</h1>
      {passwordResetDone && !challenge && (
        <p role="status" className="mb-4 rounded bg-green-50 px-3 py-2 text-sm text-green-800">
          Password reset complete. You have been signed out everywhere — log in with your
          new password.
        </p>
      )}
      {error && (
        <p role="alert" className="mb-4 rounded bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}
      {challenge ? (
        <form onSubmit={onCodeSubmit} className="space-y-3">
          <p className="text-sm text-neutral-600">
            Enter the 6-digit code from your authenticator app — or one of your recovery
            codes if you no longer have the app.
          </p>
          <div>
            <label htmlFor="login-totp-code" className="mb-1 block text-sm font-medium">
              Two-factor code
            </label>
            <input
              id="login-totp-code"
              type="text"
              autoComplete="one-time-code"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
            />
          </div>
          <button
            type="submit"
            disabled={pending}
            className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {pending ? 'Verifying…' : 'Verify code'}
          </button>
          <button
            type="button"
            onClick={() => {
              setChallenge(null);
              setCode('');
              setError(null);
            }}
            className="w-full rounded px-3 py-2 text-sm text-neutral-600 hover:underline"
          >
            Back to login
          </button>
        </form>
      ) : (
        <>
          <form onSubmit={onSubmit} className="space-y-3">
            <div>
              <label htmlFor="login-identifier" className="mb-1 block text-sm font-medium">
                Username or email
              </label>
              <input
                id="login-identifier"
                type="text"
                autoComplete="username"
                value={usernameOrEmail}
                onChange={(e) => setUsernameOrEmail(e.target.value)}
                className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
              />
            </div>
            <div>
              <label htmlFor="login-password" className="mb-1 block text-sm font-medium">
                Password
              </label>
              <input
                id="login-password"
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full rounded border border-neutral-300 px-3 py-2 text-sm"
              />
            </div>
            <button
              type="submit"
              disabled={pending}
              className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
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
