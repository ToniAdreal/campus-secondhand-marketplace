import { create } from 'zustand';
import { setAccessToken } from '../api/client';

interface AuthState {
  userId: number | null;
  login: (userId: number, accessToken: string) => void;
  logout: () => void;
}

/** UI/auth state only — server state lives in TanStack Query. */
export const useAuthStore = create<AuthState>((set) => ({
  userId: null,
  login: (userId, accessToken) => {
    setAccessToken(accessToken);
    set({ userId });
  },
  logout: () => {
    setAccessToken(null);
    set({ userId: null });
  },
}));
