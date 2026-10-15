import axios from 'axios';
import { useState } from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import {
  useLoginHistory,
  useRevokeOtherSessions,
  useRevokeSession,
  useSessions,
  type AuthSession,
  type LoginEvent,
} from '../api/auth';
import { setAccessToken } from '../api/client';
import { queryClient } from '../queryClient';
import { useAuthStore } from '../store/useAuthStore';

/**
 * My sessions — the per-session device list for the caller's live
 * refresh-token sessions (GET /api/auth/sessions, backlog #62), at
 * /settings/sessions. One row per device/browser: device label
 * (User-Agent), IP, and last-active time; the session this browser
 * presented is flagged "This device".
 *
 * Revoking a row calls DELETE /api/auth/sessions/{id}, which kills only
 * that session's refresh-token family. When more than one session exists,
 * a "Revoke all other sessions" button (POST
 * /api/auth/sessions/revoke-others, backlog #163) kills every other
 * family at once and reports the server's revoked count; this device
 * stays signed in. Revoking the CURRENT session signs
 * this tab out (its refresh cookie is dead), so it asks for confirmation
 * first and then clears local auth state and bounces to /login — without
 * calling POST /api/auth/logout, which would revoke every session.
 *
 * Below the sessions list, a "Login history" section (backlog #171)
 * renders the caller's recorded login outcomes from
 * GET /api/auth/login-history — outcome, device, IP and time, newest
 * first, first page only. It is the user's own sign-in evidence:
 * failed attempts appear here even though they answered 401/423,
 * because the backend records them in their own transaction.
 *
 * Auth: anonymous visitors bounce to /login before any request fires,
 * and a 401 mid-session (credential + cookie both died) bounces too,
 * mirroring MyOrdersPage.
 */

function isUnauthorized(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 401;
}

/** Maps a failed sessions call to UI copy; envelope messages pass through verbatim. */
function describeSessionError(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const message = (err.response?.data as { message?: string } | undefined)?.message;
    if (message) return message;
    if (!err.response) return 'Network error — is the backend running?';
  }
  return 'Something went wrong. Please try again.';
}

function deviceLabel(session: AuthSession): string {
  return session.userAgent ?? 'Unknown device';
}

function loginEventDeviceLabel(event: LoginEvent): string {
  return event.userAgent ?? 'Unknown device';
}

export default function SessionsPage() {
  const location = useLocation();
  const user = useAuthStore((s) => s.user);
  // Anonymous visitors never fire the list request — they bounce to
  // /login on this render; the mid-session 401 path below still handles
  // the credential-died-after-login case.
  const sessions = useSessions(user !== null);
  const loginHistory = useLoginHistory(user !== null);
  const revoke = useRevokeSession();
  const revokeOthers = useRevokeOtherSessions();
  const [revokeError, setRevokeError] = useState<string | null>(null);
  const [revokedOthersNote, setRevokedOthersNote] = useState<string | null>(null);

  if (!user) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  if (sessions.isError && isUnauthorized(sessions.error)) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  const list = sessions.data ?? [];

  function onRevokeOthers() {
    setRevokeError(null);
    setRevokedOthersNote(null);
    revokeOthers.mutate(undefined, {
      onSuccess: (count) => {
        // The list refetches via the mutation's invalidation; the note
        // carries the server's count so the user sees exactly what died.
        setRevokedOthersNote(
          count === 1
            ? 'Revoked 1 other session. This device stays signed in.'
            : `Revoked ${count} other sessions. This device stays signed in.`,
        );
      },
      onError: (err) => setRevokeError(describeSessionError(err)),
    });
  }

  function onRevoke(session: AuthSession) {
    setRevokeError(null);
    if (session.current) {
      // Revoking this session kills this tab's refresh cookie — the user
      // is signed out here (other devices keep their sessions). Confirm
      // before doing something that looks like a logout.
      if (
        !window.confirm(
          'Revoke this session? You will be signed out on this device and sent to the login page. Your other sessions stay signed in.',
        )
      ) {
        return;
      }
    }
    revoke.mutate(session.id, {
      onSuccess: () => {
        if (!session.current) return;
        // This tab's session is gone server-side: drop the in-memory
        // credential, the user, and the whole server-state cache, exactly
        // like logout's local cleanup — but WITHOUT the server logout
        // call, which would revoke the user's other sessions too. The
        // user=null render above then bounces to /login.
        setAccessToken(null);
        useAuthStore.setState({ user: null });
        queryClient.clear();
      },
      onError: (err) => setRevokeError(describeSessionError(err)),
    });
  }

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="mb-1 text-2xl font-bold">My sessions</h1>
      <p className="mb-4 text-sm text-neutral-500">
        Every device or browser currently signed in as{' '}
        <span className="font-medium text-neutral-700">{user.username}</span>. Revoking a
        session signs that device out; your other sessions are not affected.
      </p>
      {revokeError && (
        <p role="alert" className="mb-4 rounded bg-red-50 px-3 py-2 text-sm text-red-700">
          {revokeError}
        </p>
      )}
      {revokedOthersNote && (
        <p role="status" className="mb-4 rounded bg-green-50 px-3 py-2 text-sm text-green-700">
          {revokedOthersNote}
        </p>
      )}
      {list.length > 1 && (
        <div className="mb-4">
          <button
            type="button"
            onClick={onRevokeOthers}
            disabled={revokeOthers.isLoading}
            className="rounded bg-red-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
          >
            Revoke all other sessions
          </button>
        </div>
      )}
      {sessions.isLoading && <p>Loading sessions…</p>}
      {sessions.isError && (
        <p role="alert" className="text-red-600">
          {describeSessionError(sessions.error)}
        </p>
      )}
      {sessions.isSuccess && list.length === 0 && (
        <div className="rounded-lg bg-white p-6 text-center shadow-sm">
          <p className="text-neutral-600">No active sessions.</p>
        </div>
      )}
      {list.length > 0 && (
        <ul className="space-y-3">
          {list.map((session) => (
            <li
              key={session.id}
              className="flex items-start justify-between gap-3 rounded-lg bg-white p-4 shadow-sm"
            >
              <div>
                <p className="font-medium">
                  {deviceLabel(session)}
                  {session.current && (
                    <span className="ml-2 rounded bg-green-100 px-2 py-0.5 text-xs font-medium text-green-800">
                      This device
                    </span>
                  )}
                </p>
                <p className="mt-1 text-sm text-neutral-500">
                  IP: {session.ipAddress ?? 'Unknown IP'}
                </p>
                <p className="text-sm text-neutral-500">
                  Last active: {new Date(session.lastActiveAt).toLocaleString()}
                </p>
                <p className="text-sm text-neutral-500">
                  Signed in: {new Date(session.createdAt).toLocaleString()}
                </p>
              </div>
              <button
                type="button"
                aria-label={`Revoke session on ${deviceLabel(session)}`}
                onClick={() => onRevoke(session)}
                disabled={revoke.isLoading}
                className="shrink-0 rounded bg-red-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
              >
                Revoke
              </button>
            </li>
          ))}
        </ul>
      )}
      <h2 className="mb-1 mt-8 text-xl font-bold">Login history</h2>
      <p className="mb-4 text-sm text-neutral-500">
        Recent sign-in attempts for your account, newest first — including failed
        attempts, so you can spot sign-ins you do not recognize.
      </p>
      {loginHistory.isLoading && <p>Loading login history…</p>}
      {loginHistory.isError && (
        <p role="alert" className="text-red-600">
          {describeSessionError(loginHistory.error)}
        </p>
      )}
      {loginHistory.isSuccess && (loginHistory.data.content ?? []).length === 0 && (
        <div className="rounded-lg bg-white p-6 text-center shadow-sm">
          <p className="text-neutral-600">No login history yet.</p>
        </div>
      )}
      {(loginHistory.data?.content ?? []).length > 0 && (
        <ul className="space-y-3">
          {(loginHistory.data?.content ?? []).map((event) => (
            <li key={event.id} className="rounded-lg bg-white p-4 shadow-sm">
              <p className="font-medium">{event.outcome}</p>
              <p className="mt-1 text-sm text-neutral-500">
                Device: {loginEventDeviceLabel(event)}
              </p>
              <p className="text-sm text-neutral-500">
                IP: {event.ipAddress ?? 'Unknown IP'}
              </p>
              <p className="text-sm text-neutral-500">
                Time: {new Date(event.createdAt).toLocaleString()}
              </p>
            </li>
          ))}
        </ul>
      )}
      <p className="mt-4 text-sm">
        <Link to="/settings" className="text-blue-600 hover:underline">
          Back to settings
        </Link>
      </p>
    </div>
  );
}
