import { create } from 'zustand';
import { setAccessToken } from '../api/client';
import { logout as serverLogout } from '../api/auth';
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
}));
