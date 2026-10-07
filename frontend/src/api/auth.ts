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
