/**
 * Theme preference (backlog #227): light / dark / system, default system.
 *
 * The contract lives in three places that must agree, and the test in
 * `theme.test.ts` pins all three:
 *
 * 1. `STORAGE_KEY` — the localStorage key the preference is persisted under.
 * 2. `resolveTheme` — stored preference beats the OS media query; an
 *    unknown stored value falls back to system (never a crash, never a
 *    stuck theme from a corrupted value).
 * 3. The tiny inline script in `index.html` — a self-contained copy of
 *    this resolution (same key, same media query, same `dark` class on
 *    the root element) that runs before first paint so there is no flash
 *    of the wrong theme. `main.tsx` re-applies the same resolution on
 *    boot as the safety net for environments where the inline script
 *    cannot run (see the README #227 row on the opt-in CSP).
 *
 * `dark` is a class on `document.documentElement`, which is what
 * Tailwind 3's `darkMode: 'class'` (tailwind.config.js) keys off — the
 * palette itself is `dark:` variants on the shared surfaces, not a
 * second stylesheet. `colorScheme` is set alongside the class so native
 * form controls (scrollbars, inputs) follow the theme too.
 */

export type ThemePreference = 'light' | 'dark' | 'system';

export type ResolvedTheme = 'light' | 'dark';

export const THEME_STORAGE_KEY = 'campus-marketplace-theme';

export const THEME_PREFERENCES: ThemePreference[] = ['light', 'dark', 'system'];

function isThemePreference(value: unknown): value is ThemePreference {
  return value === 'light' || value === 'dark' || value === 'system';
}

/** Stored preference wins; `system` (or a corrupt value) follows the OS. */
export function resolveTheme(preference: ThemePreference, systemPrefersDark: boolean): ResolvedTheme {
  if (preference === 'light') return 'light';
  if (preference === 'dark') return 'dark';
  return systemPrefersDark ? 'dark' : 'light';
}

/** Reads the persisted preference; anything unreadable means `system`. */
export function readStoredTheme(): ThemePreference {
  try {
    if (typeof window === 'undefined' || !window.localStorage) return 'system';
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
    return isThemePreference(stored) ? stored : 'system';
  } catch {
    return 'system';
  }
}

/** The OS media query, guarded — jsdom and non-browser contexts lack it. */
export function systemPrefersDark(): boolean {
  try {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  } catch {
    return false;
  }
}

/** Applies the resolved theme to the root element; idempotent. */
export function applyTheme(preference: ThemePreference): ResolvedTheme {
  const resolved = resolveTheme(preference, systemPrefersDark());
  if (typeof document !== 'undefined') {
    document.documentElement.classList.toggle('dark', resolved === 'dark');
    document.documentElement.style.colorScheme = resolved;
  }
  return resolved;
}

/** Persists a preference and applies it immediately. */
export function setStoredTheme(preference: ThemePreference): ResolvedTheme {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.setItem(THEME_STORAGE_KEY, preference);
    }
  } catch {
    // A denied/unavailable storage must not break the toggle — the
    // in-memory theme still applies for this session.
  }
  return applyTheme(preference);
}
