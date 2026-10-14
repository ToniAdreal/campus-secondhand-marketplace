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

export interface RegisterPayload {
  username: string;
  email: string;
  password: string;
}

/**
 * Discriminated outcome of POST /api/auth/register (backlog #138): with
 * the backend's email-verification gate on (the default), registration
 * answers 201 with NO session — the account exists but cannot log in
 * until the address is confirmed — so the outcome is
 * `verification-required` carrying just the user summary. With the gate
 * off, the answer is the normal token pair and the outcome is
 * `authenticated`, exactly like a finished login.
 */
export type RegisterOutcome =
  | ({ kind: 'authenticated' } & LoginResult)
  | { kind: 'verification-required'; user: AuthUser };

interface RegisterPendingBody {
  user: AuthUser;
  emailVerificationRequired: boolean;
}

/**
 * POST /api/auth/register (backlog #138). Goes through the shared `api`
 * instance (withCredentials) like login, so when the gate is off the
 * httpOnly `refresh_token` cookie from the 200 response is stored by the
 * browser automatically. When verification is required the 201 response
 * carries no token at all — callers must render the "check your inbox"
 * state, never a logged-in one.
 *
 * Throws the Axios error on failure (400 weak password / validation, 409
 * duplicate username or email — the {code,message,data} envelope).
 */
export async function register(payload: RegisterPayload): Promise<RegisterOutcome> {
  const res = await api.post<ApiResponse<AuthResponseBody | RegisterPendingBody>>(
    '/auth/register',
    payload,
  );
  const body = res.data.data;
  if (body && 'emailVerificationRequired' in body && body.emailVerificationRequired) {
    return { kind: 'verification-required', user: (body as RegisterPendingBody).user };
  }
  const auth = body as AuthResponseBody;
  return { kind: 'authenticated', user: auth.user, accessToken: auth.accessToken };
}

export interface ConfirmEmailVerificationPayload {
  token: string;
}

/**
 * POST /api/auth/email-verification/confirm (backlog #138). The token
 * from the mailed link IS the credential: on success the account's
 * verified flag flips on. No session is issued — the user logs in with
 * their password afterwards. Unknown, used and expired tokens all fail
 * with the identical 400, so callers map them to one generic message.
 *
 * Throws the Axios error on failure (the {code,message,data} envelope).
 */
export async function confirmEmailVerification(
  payload: ConfirmEmailVerificationPayload,
): Promise<void> {
  await api.post('/auth/email-verification/confirm', payload);
}

export interface ResendEmailVerificationPayload {
  email: string;
}

/**
 * POST /api/auth/email-verification/resend (backlog #138). The backend
 * ALWAYS answers the identical 200 envelope whether or not the address
 * belongs to an unverified account (no enumeration oracle), so a resolved
 * promise tells the caller nothing about the account — the UI must render
 * the same state for every address. A fresh token supersedes any earlier
 * link; it is delivered through the backend's mail seam only and never
 * appears in this response.
 *
 * Throws the Axios error on failure (e.g. 400 on a malformed address,
 * 429 when the shared verification bucket is exhausted).
 */
export async function resendEmailVerification(
  payload: ResendEmailVerificationPayload,
): Promise<void> {
  await api.post('/auth/email-verification/resend', payload);
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

export interface DisableTotpPayload {
  currentPassword: string;
  code: string;
}

/**
 * POST /api/auth/2fa/disable (authenticated, backlog #121). Turns 2FA off
 * for the caller — the reverse of enable. Because disabling strips a
 * security factor, the payload must prove both factors: the current
 * password AND a second factor in the single `code` field — either the
 * current 6-digit TOTP code or one of the unused recovery codes (a
 * recovery code used here is consumed; the whole set is deleted with the
 * enrollment anyway). On success the backend clears the secret and the
 * recovery set, and the current session survives untouched.
 *
 * A wrong password and a wrong code fail with the identical 401 (no
 * oracle); disabling when 2FA is not enabled fails with 422; repeated
 * failed second-factor attempts feed the shared #101 lockout (423).
 *
 * Throws the Axios error on failure (the {code,message,data} envelope).
 */
export async function disableTotp(payload: DisableTotpPayload): Promise<void> {
  await api.post('/auth/2fa/disable', payload);
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

export interface ChangeEmailPayload {
  currentPassword: string;
  newEmail: string;
}

/**
 * POST /api/auth/email-change (backlog #150). Changes the account email:
 * the current password must match (a wrong one fails with the same 401 as
 * everywhere else — no oracle) and the new address must not be taken
 * (409). On success the backend revokes EVERY session (this one too),
 * clears the refresh cookie, and flips the account back to unverified —
 * no fresh credential is returned, so the caller must treat the user as
 * signed out and send them to verify the new address, then sign in.
 *
 * Throws the Axios error on failure; the backend wraps failures in the
 * {code,message,data} envelope.
 */
export async function changeEmail(payload: ChangeEmailPayload): Promise<void> {
  await api.post<ApiResponse<null>>('/auth/email-change', payload);
}

export interface CloseAccountPayload {
  currentPassword: string;
  /** Required only when the account has TOTP 2FA enabled; omit otherwise. */
  code?: string;
}

/**
 * DELETE /api/auth/me (backlog #158). Closes the caller's own account:
 * the current password must match, and a TOTP-enabled account must also
 * present a valid TOTP or recovery code in `code` — every proof failure
 * fails with the identical 401 (no oracle). On success the backend
 * revokes EVERY session (this one too), withdraws the caller's AVAILABLE
 * listings, and anonymizes the user row to a `deleted-<id>` tombstone
 * (order history is never rewritten) — no credential is returned, so
 * the caller must treat the user as signed out for good and drop the
 * local credential. The original username/email are freed for a fresh
 * registration.
 *
 * Throws the Axios error on failure; the backend wraps failures in the
 * {code,message,data} envelope.
 */
export async function closeAccount(payload: CloseAccountPayload): Promise<void> {
  await api.delete<ApiResponse<null>>('/auth/me', { data: payload });
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
 * POST /api/auth/sessions/revoke-others. Revokes every session of the
 * caller EXCEPT the one behind this browser's httpOnly refresh cookie
 * (backlog #163) — the "sign out everywhere else" action. The current
 * session is identified by the cookie server-side, never by an id this
 * client supplies. Resolves with how many sessions were revoked.
 *
 * Throws the Axios error on failure.
 */
export async function revokeOtherSessions(): Promise<number> {
  const res = await api.post<ApiResponse<{ revokedCount: number }>>(
    '/auth/sessions/revoke-others',
  );
  return res.data.data.revokedCount;
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

/**
 * POST /api/auth/sessions/revoke-others as a mutation (backlog #163).
 * On success the list is invalidated so it refetches the server's truth —
 * only the current session's row survives; the mutation result carries
 * the revoked count for the page's confirmation copy.
 */
export function useRevokeOtherSessions() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => revokeOtherSessions(),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: sessionKeys.all });
    },
  });
}
