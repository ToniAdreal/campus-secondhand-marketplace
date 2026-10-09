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

/** The signed second-factor challenge from a 202 login (backlog #100). */
export interface TotpChallenge {
  challenge: string;
  expiresAt: string;
}

/**
 * Discriminated outcome of POST /api/auth/login (backlog #100): either the
 * login finished (`authenticated`, with the token pair) or the account has
 * TOTP 2FA enabled and the password stage only minted a challenge
 * (`totp-challenge`) that must be exchanged — with a 6-digit code or a
 * recovery code — at POST /api/auth/2fa/authenticate.
 */
export type LoginOutcome =
  | ({ kind: 'authenticated' } & LoginResult)
  | ({ kind: 'totp-challenge' } & TotpChallenge);

/**
 * POST /api/auth/login. Goes through the shared `api` instance
 * (withCredentials), so the httpOnly `refresh_token` cookie from the
 * Set-Cookie header is stored and re-sent by the browser automatically —
 * page JavaScript never sees the refresh token. The access token arrives in
 * the JSON body and is kept in memory only (see client.ts).
 *
 * A 2FA-enabled account answers HTTP 202 with {challenge, expiresAt} and NO
 * token pair / refresh cookie; the two shapes are told apart by the status
 * and by the challenge field, so callers switch on `kind`.
 *
 * Throws the Axios error on failure; the backend wraps failures in the
 * {code,message,data} envelope (e.g. 401 "invalid credentials" — the same
 * message for unknown identifier and wrong password, no enumeration oracle).
 */
export async function login(payload: LoginPayload): Promise<LoginOutcome> {
  const res = await api.post<ApiResponse<AuthResponseBody | TotpChallenge>>(
    '/auth/login',
    payload,
  );
  const body = res.data.data;
  if (res.status === 202 || (body && 'challenge' in body)) {
    const challenge = body as TotpChallenge;
    return { kind: 'totp-challenge', challenge: challenge.challenge, expiresAt: challenge.expiresAt };
  }
  const auth = body as AuthResponseBody;
  return { kind: 'authenticated', user: auth.user, accessToken: auth.accessToken };
}

export interface AuthenticateTotpPayload {
  challenge: string;
  code: string;
}

/**
 * POST /api/auth/2fa/authenticate (backlog #100). Exchanges the 202 login
 * challenge plus a code for the normal token pair (httpOnly refresh cookie
 * included, exactly like a finished login). The single `code` field accepts
 * either the current 6-digit TOTP code or one of the one-time recovery
 * codes issued at enable time — a successful recovery-code exchange
 * consumes that code. A bad/expired challenge or a wrong code fails with
 * the identical 401, so callers show one generic message.
 *
 * Throws the Axios error on failure (the {code,message,data} envelope).
 */
export async function authenticateTotp(
  payload: AuthenticateTotpPayload,
): Promise<LoginResult> {
  const res = await api.post<ApiResponse<AuthResponseBody>>(
    '/auth/2fa/authenticate',
    payload,
  );
  const body = res.data.data;
  return { user: body.user, accessToken: body.accessToken };
}

export interface TotpSetup {
  secret: string;
  otpauthUri: string;
}

/**
 * POST /api/auth/2fa/setup (authenticated, backlog #100). Starts TOTP
 * enrollment: returns the Base32 shared secret and the otpauth:// URI to
 * enter into an authenticator app (rendered as text — no QR library).
 * Re-running setup regenerates the secret and resets enrollment.
 *
 * Throws the Axios error on failure.
 */
export async function setupTotp(): Promise<TotpSetup> {
  const res = await api.post<ApiResponse<TotpSetup>>('/auth/2fa/setup');
  const body = res.data.data;
  return { secret: body.secret, otpauthUri: body.otpauthUri };
}

export interface EnableTotpPayload {
  code: string;
}

export interface EnableTotpResult {
  recoveryCodes: string[];
}

/**
 * POST /api/auth/2fa/enable (authenticated, backlog #100). A valid 6-digit
 * code (checked against the setup secret) flips 2FA on. The response
 * carries the account's 10 one-time recovery codes — shown ONCE, only
 * their hashes are stored server-side — so callers must present them for
 * saving and never expect to read them back. A wrong code → 400.
 *
 * Throws the Axios error on failure (the {code,message,data} envelope).
 */
export async function enableTotp(payload: EnableTotpPayload): Promise<EnableTotpResult> {
  const res = await api.post<ApiResponse<EnableTotpResult>>('/auth/2fa/enable', payload);
  return { recoveryCodes: res.data.data.recoveryCodes };
}

/**
 * GET /api/auth/2fa/recovery-codes/count (authenticated, backlog #100).
 * Returns how many of the caller's recovery codes remain unused — a count
 * only; the codes themselves are never readable back after enable.
 *
 * Throws the Axios error on failure; callers degrade to no readout.
 */
export async function recoveryCodeCount(): Promise<number> {
  const res = await api.get<ApiResponse<{ remaining: number }>>(
    '/auth/2fa/recovery-codes/count',
  );
  return res.data.data.remaining;
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

export interface RequestPasswordResetPayload {
  usernameOrEmail: string;
}

/**
 * POST /api/auth/password-reset (backlog #99). Starts a password reset:
 * the backend ALWAYS answers the identical 200 envelope whether or not
 * the identifier belongs to an account (no enumeration oracle), so a
 * resolved promise tells the caller nothing about the account — the UI
 * must render the same success state for every identifier. The token is
 * delivered through the backend's mail seam only; it never appears in
 * this response.
 *
 * Throws the Axios error on failure (e.g. 400 on a blank identifier,
 * 429 when the shared reset bucket is exhausted).
 */
export async function requestPasswordReset(
  payload: RequestPasswordResetPayload,
): Promise<void> {
  await api.post('/auth/password-reset', payload);
}

export interface ConfirmPasswordResetPayload {
  token: string;
  newPassword: string;
}

/**
 * POST /api/auth/password-reset/confirm (backlog #99). The token IS the
 * credential: on success the backend consumes it, applies the strength
 * policy to the new password (weak → 400 with the reason verbatim; the
 * token is NOT consumed by a 400), bumps token_version and revokes every
 * refresh family, so all pre-reset sessions die. Unknown, used or
 * expired tokens all fail with the identical 401 — callers must map
 * them to one generic message, never distinguishing them.
 *
 * Throws the Axios error on failure; the backend wraps failures in the
 * {code,message,data} envelope.
 */
export async function confirmPasswordReset(
  payload: ConfirmPasswordResetPayload,
): Promise<void> {
  await api.post('/auth/password-reset/confirm', payload);
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
