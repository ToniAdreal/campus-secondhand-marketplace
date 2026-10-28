import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, setAccessToken } from './client';
import type { ApiResponse, Page } from './items';

export interface AuthUser {
  id: number;
  username: string;
  email: string;
  roles: string[];
  /** Whether TOTP 2FA is enabled on the account (GET /api/auth/me, backlog #218). */
  totpEnabled?: boolean;
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
  /**
   * The proofs the exchange accepts for this account (backlog #220):
   * `totp` when TOTP is enrolled, `passkey` when at least one passkey is
   * enrolled. Absent on no path the backend emits — defaulted defensively
   * to the TOTP-only shape.
   */
  factors: string[];
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
    return {
      kind: 'totp-challenge',
      challenge: challenge.challenge,
      expiresAt: challenge.expiresAt,
      // Backlog #220: the 202 advertises which second factors the account
      // holds; older responses without the field read as TOTP-only.
      factors: Array.isArray(challenge.factors) ? challenge.factors : ['totp'],
    };
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
  signal?: AbortSignal,
): Promise<void> {
  await api.post('/auth/email-verification/confirm', payload, { signal });
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
  /**
   * The passkey proof (backlog #220): the serialized
   * `PublicKeyCredential` JSON from `navigator.credentials.get()`,
   * verified against the single-use assertion challenge. When present
   * and non-blank the passkey path is taken and `code` is ignored;
   * when absent the TOTP/recovery-code path applies.
   */
  assertion?: string;
  /**
   * Trusted-device opt-in (backlog #199): when true, a SUCCESSFUL
   * exchange also trusts this device — the response sets the
   * `trusted_device` httpOnly cookie and later password logins from
   * this device skip only the TOTP step. Omitted (not sent) when
   * false/absent, matching the backend's absent-is-false contract.
   */
  rememberDevice?: boolean;
}

/**
 * POST /api/auth/2fa/authenticate (backlog #100). Exchanges the 202 login
 * challenge plus exactly one proof for the normal token pair (httpOnly
 * refresh cookie included, exactly like a finished login). The single
 * `code` field accepts either the current 6-digit TOTP code or one of the
 * one-time recovery codes issued at enable time — a successful
 * recovery-code exchange consumes that code; `assertion` (backlog #220)
 * carries a WebAuthn passkey assertion instead, and when present the
 * passkey path is taken. A bad/expired challenge or a wrong proof fails
 * with the identical 401 whatever the factor, so callers show one generic
 * message.
 *
 * Throws the Axios error on failure (the {code,message,data} envelope).
 */
export async function authenticateTotp(
  payload: AuthenticateTotpPayload,
): Promise<LoginResult> {
  const res = await api.post<ApiResponse<AuthResponseBody>>('/auth/2fa/authenticate', {
    challenge: payload.challenge,
    code: payload.code,
    // Backlog #220: the passkey proof travels in `assertion`; omitted
    // when the TOTP/recovery-code path is used.
    ...(payload.assertion ? { assertion: payload.assertion } : {}),
    // Only sent when the user opted in — absent reads as false server-side.
    ...(payload.rememberDevice ? { rememberDevice: true } : {}),
  });
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
 * Backlog #250: issuing the secret is step-up gated server-side — a
 * stale token answers the dedicated 403 step-up envelope and mints
 * nothing; the settings setup button surfaces that envelope message,
 * and a modal re-proof retry for setup itself is a follow-up.
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

// ---------------------------------------------------------------------------
// WebAuthn passkeys as an alternative second factor (backlog #220).
//
// The ceremony byte fields travel as base64url strings; the ArrayBuffer
// conversions live in ./webauthn (pure, unit-tested) and the navigator
// calls live in the pages, feature-detected — no passkey UI where the
// browser lacks WebAuthn support. Enrolment and removal are step-up gated
// server-side (a 403 step-up envelope when the presented access token's
// credential proof is stale); callers wrap the options/remove calls in
// withStepUpRetry with the typed proof.
// ---------------------------------------------------------------------------

/** Re-exported for the pages: the ceremony option shapes. */
import type {
  PasskeyAssertionOptions,
  PasskeyInfo,
  PasskeyRegistrationOptions,
} from './webauthn';
export type { PasskeyAssertionOptions, PasskeyInfo, PasskeyRegistrationOptions };

/**
 * POST /api/auth/2fa/passkeys/registration/options (authenticated, step-up
 * gated). Mints the registration ceremony options — the single-use server
 * challenge plus the rp/user descriptors — for
 * `navigator.credentials.create()`.
 */
export async function startPasskeyRegistration(): Promise<PasskeyRegistrationOptions> {
  const res = await api.post<ApiResponse<PasskeyRegistrationOptions>>(
    '/auth/2fa/passkeys/registration/options',
  );
  return res.data.data;
}

/**
 * POST /api/auth/2fa/passkeys/registration/verify (authenticated, step-up
 * gated). Completes enrolment with the serialized PublicKeyCredential JSON
 * from `navigator.credentials.create()`. Returns the enrolled credential
 * id (base64url) — the handle the settings surface lists and DELETE
 * addresses. A PASSKEY_REGISTERED audit row and a security notification
 * are recorded server-side.
 *
 * Backlog #241: `name` is the optional display-only friendly label for
 * the new row. A blank name is omitted from the body, so the server
 * stores NULL and the list renders the enrolment-date label.
 */
export async function finishPasskeyRegistration(
  credential: string,
  name?: string,
): Promise<{ credentialId: string }> {
  const trimmed = name?.trim();
  const res = await api.post<ApiResponse<{ credentialId: string }>>(
    '/auth/2fa/passkeys/registration/verify',
    trimmed ? { credential, name: trimmed } : { credential },
  );
  return res.data.data;
}

/**
 * GET /api/auth/2fa/passkeys (authenticated). Lists the caller's enrolled
 * passkeys — id, credential id, enrolment instant and last verified
 * signature counter. The public keys are never exposed.
 */
export async function listPasskeys(): Promise<PasskeyInfo[]> {
  const res = await api.get<ApiResponse<PasskeyInfo[]>>('/auth/2fa/passkeys');
  return res.data.data;
}

/**
 * DELETE /api/auth/2fa/passkeys/{id} (authenticated, step-up gated).
 * Removes one of the caller's passkeys; a PASSKEY_REMOVED audit row and a
 * security notification are recorded server-side. Unknown ids — or ids
 * belonging to another user — are 404, never a cross-user oracle.
 */
export async function deletePasskey(id: number): Promise<void> {
  await api.delete(`/auth/2fa/passkeys/${id}`);
}

/**
 * PATCH /api/auth/2fa/passkeys/{id} (authenticated, step-up gated —
 * backlog #241). Renames one of the caller's passkeys: the display-only
 * friendly label. A blank name clears the label (the server stores
 * NULL and the list falls back to the enrolment-date label); an
 * overlong name is a 400. Unknown ids — or ids belonging to another
 * user — are 404, never a cross-user oracle. Returns the updated row.
 */
export async function renamePasskey(id: number, name: string): Promise<PasskeyInfo> {
  const res = await api.patch<ApiResponse<PasskeyInfo>>(`/auth/2fa/passkeys/${id}`, { name });
  return res.data.data;
}

/**
 * POST /api/auth/2fa/passkeys/assertion/options (public — the caller is not
 * authenticated yet). Opens a passkey assertion ceremony: the 202 login
 * challenge token resolves to the challenged account, which must hold at
 * least one passkey. Returns the single-use assertion challenge plus the
 * `allowCredentials` list for `navigator.credentials.get()`; the
 * resulting assertion is exchanged — with the same 202 challenge — at
 * POST /api/auth/2fa/authenticate. A bad/expired 202 challenge or no
 * passkeys enrolled answers the identical 401.
 */
export async function startPasskeyAssertion(
  challenge: string,
): Promise<PasskeyAssertionOptions> {
  const res = await api.post<ApiResponse<PasskeyAssertionOptions>>(
    '/auth/2fa/passkeys/assertion/options',
    { challenge },
  );
  return res.data.data;
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
 * POST /api/auth/email-change (backlog #150, reworked by #208).
 * REQUESTS an email change: the current password must match (a wrong
 * one fails with the same 401 as everywhere else — no oracle) and the
 * new address must not be taken (409, re-checked at confirm). On
 * success NOTHING has switched yet: the backend stages the new address
 * as pending, mails a single-use token to the NEW address (see
 * confirmEmailChange), and notifies the holder in-app at the old one.
 * Every session stays alive — the caller stays signed in and shows the
 * "check your new inbox" state.
 *
 * Throws the Axios error on failure; the backend wraps failures in the
 * {code,message,data} envelope.
 */
export async function changeEmail(payload: ChangeEmailPayload): Promise<void> {
  await api.post<ApiResponse<null>>('/auth/email-change', payload);
}

export interface ChangeUsernamePayload {
  username: string;
}

/**
 * POST /api/auth/username (backlog #256). The rename is immediate —
 * unlike email there is no staged/confirm step — and the session stays
 * alive: no token rotation, no refresh revocation. The response carries
 * the updated user summary, which callers store so the header shows the
 * new name at once (an already-issued access token's username claim
 * goes stale until it expires or rotates, ≤15 min).
 *
 * Throws the Axios error on failure; the backend wraps failures in the
 * {code,message,data} envelope (400 bad format, 409 taken name or the
 * caller's own current name).
 */
export async function changeUsername(payload: ChangeUsernamePayload): Promise<AuthUser> {
  const res = await api.post<ApiResponse<AuthUser>>('/auth/username', payload);
  return res.data.data;
}

export interface ConfirmEmailChangePayload {
  token: string;
}

/**
 * POST /api/auth/email-change/confirm (backlog #208). The token from
 * the link mailed to the NEW address is the credential: confirming
 * switches the account's address to it, revokes every pre-switch
 * session and clears the refresh cookie — no session is issued here,
 * so afterwards the user signs in at the new address. Unknown, used
 * and expired tokens all fail with the identical 401; an address taken
 * between request and confirm fails 409.
 */
export async function confirmEmailChange(
  payload: ConfirmEmailChangePayload,
  signal?: AbortSignal,
): Promise<void> {
  await api.post<ApiResponse<null>>('/auth/email-change/confirm', payload, {
    signal,
  });
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
 * One trusted device, mirroring the backend's TrustedDeviceDto
 * (GET /api/auth/trusted-devices, backlog #199). A trusted device still
 * requires the password at login — trust skips only the TOTP step. The
 * token and its hashes are never exposed: `id` is the trust row id and
 * the path id for DELETE /api/auth/trusted-devices/{id}. `lastUsedAt`
 * is null until the first login that skipped the challenge via this
 * trust; `expiresAt` slides forward on every trusted use.
 */
export interface TrustedDevice {
  id: number;
  userAgent: string | null;
  ipAddress: string | null;
  createdAt: string;
  lastUsedAt: string | null;
  expiresAt: string;
}

/**
 * GET /api/auth/trusted-devices. Lists the caller's trusted devices,
 * newest first (server-side order). Requires authentication.
 *
 * Throws the Axios error on failure (401 when no session exists).
 */
export async function listTrustedDevices(): Promise<TrustedDevice[]> {
  const res = await api.get<ApiResponse<TrustedDevice[]>>('/auth/trusted-devices');
  return res.data.data;
}

/**
 * DELETE /api/auth/trusted-devices/{id}. Revokes one trusted device:
 * its cookie buys nothing at the next login and the full TOTP
 * challenge applies again. Unknown ids — or ids belonging to another
 * user — fail with 404 (no cross-user oracle).
 *
 * Throws the Axios error on failure.
 */
export async function revokeTrustedDevice(id: number): Promise<void> {
  await api.delete(`/auth/trusted-devices/${id}`);
}

/**
 * The verdict of one recorded login attempt, mirroring the backend's
 * LoginOutcome enum (backlog #171): SUCCESS — a token pair was issued;
 * BAD_PASSWORD — the account exists but the password did not match (a
 * disabled account folds into this value, exactly like the 401);
 * UNKNOWN_USER — no account matched (recorded with no owner and never
 * returned by the history API, so it cannot appear here in practice);
 * LOCKED — refused by a lockout, or the failure that tripped one;
 * TOTP_REQUIRED — password verified, second factor outstanding;
 * TOTP_FAILED — the second-factor exchange failed; EMAIL_UNVERIFIED —
 * password verified but the address is not confirmed yet (#138's 403).
 */
export type LoginEventOutcome =
  | 'SUCCESS'
  | 'BAD_PASSWORD'
  | 'UNKNOWN_USER'
  | 'LOCKED'
  | 'TOTP_REQUIRED'
  | 'TOTP_FAILED'
  | 'EMAIL_UNVERIFIED';

/**
 * One row of the caller's own login history, mirroring the backend's
 * LoginEventDto (GET /api/auth/login-history, backlog #171).
 * `identifier` is the attempted identifier as typed (the account's
 * username on second-factor rows); `userAgent`/`ipAddress` are the
 * device label and client IP captured at the attempt — null when the
 * client sent no User-Agent / the address was unknown.
 */
export interface LoginEvent {
  id: number;
  outcome: LoginEventOutcome;
  identifier: string;
  ipAddress: string | null;
  userAgent: string | null;
  createdAt: string;
}

/**
 * GET /api/auth/login-history. The caller's own recorded login outcomes,
 * newest first, as one page of the shared Page envelope. Only rows that
 * belong to the caller are ever returned — unknown-identifier rows have
 * no owner and are never exposed here. The page renders the first page
 * (20 rows) only; there is deliberately no "load more" in v1.
 *
 * Throws the Axios error on failure (401 when no session exists).
 */
export async function getLoginHistory(page = 0, size = 20): Promise<Page<LoginEvent>> {
  const res = await api.get<ApiResponse<Page<LoginEvent>>>('/auth/login-history', {
    params: { page, size },
  });
  return res.data.data;
}

/**
 * Query keys for the sessions subtree. Everything hangs off `['sessions']`
 * so a single prefix invalidation refetches the list after a revoke.
 */
export const sessionKeys = {
  all: ['sessions'] as const,
  list: () => [...sessionKeys.all, 'list'] as const,
  loginHistory: () => [...sessionKeys.all, 'login-history'] as const,
  trustedDevices: () => [...sessionKeys.all, 'trusted-devices'] as const,
};

/** GET /api/auth/login-history as a query (first page only — see getLoginHistory); disabled for anonymous visitors so no request fires before the login bounce. */
export function useLoginHistory(enabled = true) {
  return useQuery({
    queryKey: sessionKeys.loginHistory(),
    queryFn: () => getLoginHistory(),
    enabled,
  });
}

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

/** GET /api/auth/trusted-devices as a query (backlog #199); disabled for anonymous visitors so no request fires before the login bounce. */
export function useTrustedDevices(enabled = true) {
  return useQuery({
    queryKey: sessionKeys.trustedDevices(),
    queryFn: () => listTrustedDevices(),
    enabled,
  });
}

/**
 * DELETE /api/auth/trusted-devices/{id} as a mutation (backlog #199).
 * On success the revoked row is dropped from the cached list
 * immediately and the sessions subtree is invalidated so it refetches
 * the server's truth — mirroring useRevokeSession.
 */
export function useRevokeTrustedDevice() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => revokeTrustedDevice(id),
    onSuccess: (_data, id) => {
      queryClient.setQueryData<TrustedDevice[]>(sessionKeys.trustedDevices(), (old) =>
        old?.filter((device) => device.id !== id),
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

/**
 * POST /api/auth/step-up — step-up re-authentication for sensitive actions
 * (backlog #218). The caller re-proves the current password (plus a
 * TOTP/recovery code when 2FA is on) and the backend mints a fresh
 * access-token-only credential whose `aut` claim is now: no new session is
 * created, the session cap does not count it, and the endpoint itself is
 * exempt from the freshness gate (it IS the re-proof). Its own per-user
 * rate-limit bucket (5/min) answers 429 on abuse.
 *
 * Throws the Axios error on failure (401 wrong proof, 423 lockout, 429).
 */
export interface StepUpPayload {
  currentPassword: string;
  code?: string;
}

export interface StepUpResult {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
}

export async function stepUp(payload: StepUpPayload): Promise<StepUpResult> {
  const res = await api.post<ApiResponse<StepUpResult>>('/auth/step-up', payload);
  return res.data.data;
}

/**
 * The backend's dedicated step-up 403 message (StepUpRequiredException,
 * backlog #218). It is deliberately distinct from a 401: the token is
 * valid and the session alive, but its last credential proof is too old
 * for the sensitive action attempted.
 */
export const STEP_UP_REQUIRED_MESSAGE = 're-authentication required';

/**
 * True when the failure is the dedicated 403 step-up envelope: the gated
 * action must re-prove the credential and retry exactly once. Any other
 * 403 (forbidden by role/ownership) or any 401 is NOT step-up.
 */
export function isStepUpRequired(error: unknown): boolean {
  if (error == null || typeof error !== 'object') return false;
  const response = (
    error as { response?: { data?: { code?: number; message?: string } } }
  ).response;
  return (
    response?.data?.code === 403 &&
    typeof response.data.message === 'string' &&
    response.data.message.includes(STEP_UP_REQUIRED_MESSAGE)
  );
}

/**
 * Maps a failed POST /api/auth/step-up to a user-facing message. The
 * backend speaks the {code,message,data} envelope; the 403 step-up
 * envelope never surfaces here (that is the trigger, not the proof
 * failing) — 401 means the password/code was wrong, 423 means the
 * account's attempt lockout is active, 429 means the endpoint's own
 * per-user bucket is full.
 */
export function describeStepUpError(error: unknown): string {
  const response = (
    error as { response?: { data?: { code?: number; message?: string } } }
  ).response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 401) {
    return 'Current password or code is incorrect — please check and try again.';
  }
  if (code === 423) {
    return 'This account is temporarily locked after too many failed attempts — please wait a few minutes and try again.';
  }
  if (code === 429) {
    return 'Too many verification attempts — please wait a moment and try again.';
  }
  return message || 'Something went wrong. Please try again.';
}

/**
 * Runs a sensitive action, transparently re-proving with the given
 * credentials and retrying exactly once when the backend answers the
 * dedicated 403 step-up envelope (backlog #218). For actions whose form
 * already collected the proof (password + optional code) this avoids a
 * second password prompt: the proof the user just typed is reused. The
 * fresh credential is swapped into the in-memory token store (the modal
 * path does the same) so the retried request carries a fresh `aut` claim;
 * a second 403 propagates as a real error — there is exactly one retry,
 * never a loop.
 *
 * Actions with no inline proof (e.g. revoke-others on the sessions page)
 * use the StepUpModal instead — same swap, same retry-once discipline.
 *
 * Known limitation: if the proof uses a one-time recovery code (not a
 * TOTP code) and the token was stale, the step-up endpoint consumes the
 * code, and the retried action re-verifies it — the retry then 401s on
 * the consumed code. TOTP codes are unaffected (they are not consumed).
 * Users in this narrow case see the "incorrect" copy and can retry with
 * a fresh code; the backend deliberately re-verifies rather than
 * trusting the fresh token alone (see TotpDisableLockoutTest).
 */
export async function withStepUpRetry<T>(
  action: () => Promise<T>,
  proof: StepUpPayload,
): Promise<T> {
  try {
    return await action();
  } catch (err) {
    if (!isStepUpRequired(err)) throw err;
    const fresh = await stepUp(proof);
    setAccessToken(fresh.accessToken);
    return await action();
  }
}

/** A downloaded account data export: the blob plus the server's filename. */
export interface AccountDataDownload {
  blob: Blob;
  filename: string;
}

/** Reads one header from an Axios response (AxiosHeaders or plain object). */
function readResponseHeader(headers: unknown, name: string): string | undefined {
  if (!headers || typeof headers !== 'object') return undefined;
  const bag = headers as { get?: (n: string) => unknown } & Record<string, unknown>;
  if (typeof bag.get === 'function') {
    const value = bag.get(name);
    return value == null ? undefined : String(value);
  }
  const value = bag[name] ?? bag[name.toLowerCase()];
  return value == null ? undefined : String(value);
}

/** Step-up proof for the data-export grant (backlog #188). */
export interface ConfirmDataExportPayload {
  currentPassword: string;
  /** TOTP or recovery code — required when the account has 2FA on. */
  code?: string;
}

/** A minted data-export grant: shown once, single-use, minutes-lived. */
export interface DataExportGrant {
  grant: string;
  expiresAt: string;
}

/**
 * POST /api/users/me/export/confirm (backlog #188). Re-proves the
 * caller's current password (plus a two-factor code when 2FA is on —
 * every proof failure is the identical 401, no oracle) and mints the
 * single-use grant that authorizes exactly one download. Only the
 * grant's SHA-256 hash is stored server-side.
 *
 * Throws the Axios error on failure; the backend wraps failures in
 * the {code,message,data} envelope.
 */
export async function confirmDataExport(
  payload: ConfirmDataExportPayload,
): Promise<DataExportGrant> {
  const res = await api.post<ApiResponse<DataExportGrant>>(
    '/users/me/export/confirm',
    payload,
  );
  return res.data.data;
}

/**
 * GET /api/users/me/export (backlog #172; grant gate added by #188).
 * Downloads the caller's own data — profile, listings, orders on both
 * sides, messages, favorites and saved searches — as a single JSON
 * file. The {@link DataExportGrant} from {@link confirmDataExport}
 * rides as the X-Export-Grant header and is consumed by the download:
 * a missing, expired or replayed grant is a 401. The success answer is
 * the document itself (no envelope), served as an attachment whose
 * filename carries the user id and export date; the caller saves the
 * blob under exactly that server filename (the fallback below only
 * fires if a proxy stripped the header). The export is rate-limited
 * per user server-side (429 envelope) and audit-logged there.
 *
 * Throws the Axios error on failure; with responseType 'blob' the
 * error body is a Blob too, so callers map by status, not by body.
 */
export async function downloadMyData(grant: string): Promise<AccountDataDownload> {
  const res = await api.get<Blob>('/users/me/export', {
    responseType: 'blob',
    headers: { 'X-Export-Grant': grant },
  });
  const disposition = readResponseHeader(res.headers, 'content-disposition');
  const match = disposition?.match(/filename="([^"]+)"/);
  return { blob: res.data, filename: match?.[1] ?? 'marketplace-data-export.json' };
}
