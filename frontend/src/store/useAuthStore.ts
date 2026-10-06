import { create } from 'zustand';
import { setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';

interface AuthState {
  /** Logged-in user summary, or null when logged out. */
  user: AuthUser | null;
  /** Stores the user and keeps the access token in memory (client.ts module
   * scope — never localStorage/sessionStorage). The refresh token lives in
   * an httpOnly cookie managed by the browser (see api/client.ts). */
  login: (user: AuthUser, accessToken: string) => void;
  /** Clears the in-memory token and the user. Note: this store does not call
   * the server-side POST /api/auth/logout yet, so the httpOnly refresh
   * cookie lingers until it expires (7d) — it is useless without an access
   * token and rotates on every refresh, so a stale cookie cannot mint a
   * session on its own. */
  logout: () => void;
}

/** UI/auth state only — server state lives in TanStack Query. */
export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  login: (user, accessToken) => {
    setAccessToken(accessToken);
    set({ user });
  },
  logout: () => {
    setAccessToken(null);
    set({ user: null });
  },
}));
