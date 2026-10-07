import { create } from 'zustand';
import { setAccessToken } from '../api/client';
import { logout as serverLogout, me as fetchMe } from '../api/auth';
import { queryClient } from '../queryClient';
import type { AuthUser } from '../api/auth';

interface AuthState {
  /** Logged-in user summary, or null when logged out. */
  user: AuthUser | null;
  /** Stores the user and keeps the bearer credential in memory (client.ts
   * module scope — never localStorage/sessionStorage). The refresh token
   * lives in an httpOnly cookie managed by the browser (see api/client.ts). */
  login: (user: AuthUser, accessToken: string) => void;
  /**
   * Server-side logout: calls POST /api/auth/logout so the backend revokes
   * the refresh-token family and the browser drops the httpOnly cookie via
   * the server's expired Set-Cookie. Afterwards clears the in-memory
   * credential, the user, and the whole TanStack query cache — no other
   * user's server state may linger after logout.
   *
   * A failed server call still clears local state (the `finally`): the UI
   * must never look logged in after the user asked to log out. The error
   * propagates so callers can decide what to do (the logout button navigates
   * home either way).
   */
  logout: () => Promise<void>;
  /**
   * Session restore on boot: GET /api/auth/me through the shared api
   * instance. When the in-memory credential is missing or stale, the
   * client's 401 interceptor silently refreshes it via the httpOnly cookie
   * and retries — so a surviving cookie is enough to come back logged in
   * after a page reload. The interceptor already puts any fresh credential
   * in module scope (see client.ts), so this only stores the user.
   *
   * Never throws: a failure (no cookie, refresh rejected, network down)
   * means no session exists, so the store resets to the logged-out state —
   * the in-memory credential is cleared too, keeping the UI and the
   * request layer in agreement. Resolves the user on success, null on
   * failure.
   */
  hydrate: () => Promise<AuthUser | null>;
}

/** UI/auth state only — server state lives in TanStack Query. */
export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  login: (user, accessToken) => {
    setAccessToken(accessToken);
    set({ user });
  },
  logout: async () => {
    try {
      await serverLogout();
    } finally {
      setAccessToken(null);
      set({ user: null });
      queryClient.clear();
    }
  },
  hydrate: async () => {
    try {
      const user = await fetchMe();
      set({ user });
      return user;
    } catch {
      // No session exists (no cookie, refresh rejected, network down):
      // reset to the logged-out state. Any stale in-memory credential is
      // dropped too — the next authenticated request will silently
      // refresh from the cookie (if one exists) and re-derive state.
      setAccessToken(null);
      set({ user: null });
      return null;
    }
  },
}));
