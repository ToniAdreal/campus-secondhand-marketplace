import { create } from 'zustand';
import { readStoredLocale, setStoredLocale, type Locale } from '../locale';

/**
 * Locale preference store (backlog #431). Same shape as the other
 * frontend-only stores (useThemeStore, useCartStore): the preference is
 * local state persisted in localStorage, not a backend resource — there
 * is no server-side locale to sync, and a signed-out visitor gets a
 * language too.
 *
 * The store is initialised from storage at creation. Every change goes
 * through `setLocale`, which persists + sets state in one step, so
 * subscribed components re-render in the new language without a
 * reload. Plain `describe*Error` mappers do not subscribe — they read
 * the stored locale at call time through `t()` instead.
 */

interface LocaleState {
  locale: Locale;
  setLocale: (locale: Locale) => void;
}

export const useLocaleStore = create<LocaleState>((set) => ({
  locale: readStoredLocale(),
  setLocale: (locale) => {
    setStoredLocale(locale);
    set({ locale });
  },
}));
