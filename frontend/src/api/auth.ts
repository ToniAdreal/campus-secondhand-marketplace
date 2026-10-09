import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { ApiResponse } from './items';

export interface AuthUser {
  id: number;
  username: string;
  email: string;
  roles: string[];
}

interface AuthResponseBody {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: AuthUser;
}

export interface LoginPayload {
  usernameOrEmail: string;
  password: string;
}

export interface LoginResult {
  user: AuthUser;
  accessToken: string;
}

/**
 * POST /api/auth/login. Goes through the shared `api` instance
 * (withCredentials), so the httpOnly `refresh_token` cookie from the
 * Set-Cookie header is stored and re-sent by the browser automatically —
 * page JavaScript never sees the refresh token. The access token arrives in
 * the JSON body and is kept in memory only (see client.ts).
 *
 * Throws the Axios error on failure; the backend wraps failures in the
 * {code,message,data} envelope (e.g. 401 "invalid credentials" — the same
 * message for unknown identifier and wrong password, no enumeration oracle).
 */
export async function login(payload: LoginPayload): Promise<LoginResult> {
  const res = await api.post<ApiResponse<AuthResponseBody>>('/auth/login', payload);
  const body = res.data.data;
  return { user: body.user, accessToken: body.accessToken };
}

/**
 * POST /api/auth/logout. Goes through the shared `api` instance, which
 * attaches the in-memory bearer credential — the backend requires
 * authentication on this route (SecurityConfig): it revokes the caller's
 * whole refresh-token family server-side and answers with an expired
 * Set-Cookie that wipes the httpOnly `refresh_token` cookie. The call takes
 * no payload and the response body carries no tokens.
 *
 * Throws the Axios error on failure (e.g. 401 when the access token already
 * expired — note the client's 401 interceptor may silently refresh once and
 * then retry the logout, which is harmless: the retry just revokes the
 * rotated family instead). Callers must clear local auth state in a
 * `finally`, never only on success — a failed server call must not leave the
 * UI looking logged in.
 */
export async function logout(): Promise<void> {
  await api.post('/auth/logout');
}

export interface ChangePasswordPayload {
  currentPassword: string;
  newPassword: string;
}

/**
 * POST /api/auth/password. Changes the caller's password: the current
 * password must match (a wrong one fails with the same 401 as everywhere
 * else — no oracle), the new one must pass the strength policy (400 with
 * the reason verbatim).
 *
 * On success the backend has rotated the whole refresh-token family, so
 * every OTHER device's session is dead; the response carries the fresh
 * bearer credential for THIS session (the same AuthResponse shape as
 * login) plus the user profile — callers must store them (e.g. through
 * the store's login()) so subsequent requests keep working.
 *
 * Throws the Axios error on failure; the backend wraps failures in the
 * {code,message,data} envelope.
 */
export async function changePassword(payload: ChangePasswordPayload): Promise<LoginResult> {
  const res = await api.post<ApiResponse<AuthResponseBody>>('/auth/password', payload);
  const body = res.data.data;
  return { user: body.user, accessToken: body.accessToken };
}

/**
 * GET /api/auth/me. Returns the caller's profile (id/username/email/roles)
 * for session restore on SPA reload. When the in-memory credential is
 * missing or stale, the shared client's 401 interceptor silently refreshes
 * it via the httpOnly cookie and retries — so a surviving cookie is enough
 * to come back logged in. The interceptor puts any fresh credential in
 * module scope (see client.ts); callers only need the user from here.
 *
 * Throws the Axios error when no session exists (no cookie, refresh
 * rejected) — the store's hydrate() converts that into a logged-out state.
 */
export async function me(): Promise<AuthUser> {
  const res = await api.get<ApiResponse<AuthUser>>('/auth/me');
  return res.data.data;
}

/**
 * One live refresh-token session, mirroring the backend's SessionDto
 * (GET /api/auth/sessions, backlog #62). A session is a refresh-token
 * family: `id` is the family's root jti, stable across rotations, and is
 * the path id for DELETE /api/auth/sessions/{id}. `userAgent`/`ipAddress`
 * are the device label and remote address captured at the last
 * login/refresh — null for sessions that predate the capture or clients
 * that sent no User-Agent.
 */
export interface AuthSession {
  id: string;
  userAgent: string | null;
  ipAddress: string | null;
  createdAt: string;
  lastActiveAt: string;
  /** True for the session whose refresh cookie this browser presented. */
  current: boolean;
}

/**
 * GET /api/auth/sessions. Lists the caller's live sessions, most recently
 * active first (server-side order). Requires authentication — the shared
 * client's 401 interceptor silently refreshes once, so a surviving
 * httpOnly cookie is enough.
 *
 * Throws the Axios error on failure (401 when no session exists).
 */
export async function listSessions(): Promise<AuthSession[]> {
  const res = await api.get<ApiResponse<AuthSession[]>>('/auth/sessions');
  return res.data.data;
}

/**
 * DELETE /api/auth/sessions/{id}. Revokes one session (its whole
 * refresh-token family) without touching the caller's other sessions.
 * Unknown ids — or ids belonging to another user — fail with 404 (the
 * backend gives no cross-user oracle). Revoking the caller's own current
 * session kills this browser's refresh cookie: the next refresh 401s, so
 * callers must treat it as a sign-out of this tab.
 *
 * Throws the Axios error on failure.
 */
export async function revokeSession(id: string): Promise<void> {
  await api.delete(`/auth/sessions/${encodeURIComponent(id)}`);
}

/**
 * Query keys for the sessions subtree. Everything hangs off `['sessions']`
 * so a single prefix invalidation refetches the list after a revoke.
 */
export const sessionKeys = {
  all: ['sessions'] as const,
  list: () => [...sessionKeys.all, 'list'] as const,
};

/** GET /api/auth/sessions as a query; disabled for anonymous visitors so no request fires before the login bounce. */
export function useSessions(enabled = true) {
  return useQuery({
    queryKey: sessionKeys.list(),
    queryFn: () => listSessions(),
    enabled,
  });
}

/**
 * DELETE /api/auth/sessions/{id} as a mutation. On success the revoked row
 * is dropped from the cached list immediately and the list is invalidated
 * so it refetches the server's truth — the page never patches anything
 * else (other sessions are untouched server-side too).
 */
export function useRevokeSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => revokeSession(id),
    onSuccess: (_data, id) => {
      queryClient.setQueryData<AuthSession[]>(sessionKeys.list(), (old) =>
        old?.filter((session) => session.id !== id),
      );
      void queryClient.invalidateQueries({ queryKey: sessionKeys.all });
    },
  });
}
