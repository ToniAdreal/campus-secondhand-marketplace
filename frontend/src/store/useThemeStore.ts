import { create } from 'zustand';
import {
  readStoredTheme,
  setStoredTheme,
  type ThemePreference,
} from '../theme';

/**
 * Theme preference store (backlog #227). Same shape as the other
 * frontend-only stores (useCartStore): the preference is local state
 * persisted in localStorage, not a backend resource — there is no
 * server-side theme to sync, and a signed-out visitor gets a theme too.
 *
 * The store is initialised from storage at creation and applies the
 * theme immediately, so a Settings-page toggle and the `index.html`
 * pre-paint script can never disagree about the current class: every
 * change goes through `setPreference`, which persists + applies in
 * one step. `main.tsx` also calls `applyTheme(readStoredTheme())` on
 * boot as the safety net for the inline script.
 */

interface ThemeState {
  preference: ThemePreference;
  setPreference: (preference: ThemePreference) => void;
}

export const useThemeStore = create<ThemeState>((set) => ({
  preference: readStoredTheme(),
  setPreference: (preference) => {
    // setStoredTheme persists AND applies in one step, so the page
    // repaints in the new theme without a reload.
    setStoredTheme(preference);
    set({ preference });
  },
}));
