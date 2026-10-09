import axios from 'axios';
import { type FormEvent, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { login } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';

/** Reads the human message out of the backend {code,message,data} envelope. */
function envelopeMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const message = (err.response?.data as { message?: string } | undefined)?.message;
    if (message) return message;
    if (!err.response) return 'Network error — is the backend running?';
  }
  return 'Login failed';
}

export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const user = useAuthStore((s) => s.user);
  const storeLogin = useAuthStore((s) => s.login);

  const [usernameOrEmail, setUsernameOrEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  if (user) {
    // Already signed in — no reason to show the form.
    return <Navigate to="/" replace />;
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
      const { user: loggedIn, accessToken } = await login({
        usernameOrEmail: usernameOrEmail.trim(),
        password,
      });
      storeLogin(loggedIn, accessToken);
      const from = (location.state as { from?: string } | null)?.from ?? '/';
      navigate(from, { replace: true });
    } catch (err) {
      setError(envelopeMessage(err));
    } finally {
      setPending(false);
    }
  }

  const passwordResetDone =
    (location.state as { passwordReset?: boolean } | null)?.passwordReset === true;

  return (
    <div className="mx-auto max-w-sm rounded-lg bg-white p-6 shadow-sm">
      <h1 className="mb-4 text-xl font-bold">Login</h1>
      {passwordResetDone && (
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
    </div>
  );
}
