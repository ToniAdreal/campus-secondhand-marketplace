import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  THEME_STORAGE_KEY,
  applyTheme,
  readStoredTheme,
  resolveTheme,
  setStoredTheme,
} from './theme';
import { useThemeStore } from './store/useThemeStore';

/**
 * Dark-mode theme contract (backlog #227).
 *
 * Three layers are pinned here, in the order the spec demands:
 * 1. Resolution matrix — a stored light/dark preference always beats the
 *    OS media query; `system` (and any corrupt stored value) follows it.
 * 2. The shipped init script — the test reads the ACTUAL index.html,
 *    extracts its inline script, and executes it under stubbed
 *    localStorage / matchMedia, so the pre-paint script can never drift
 *    from this module's key or rule without a test failing.
 * 3. The store round-trip — setting a preference persists it and flips
 *    the `dark` class immediately (no reload needed for the repaint).
 */

function setSystemDark(matches: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: query === '(prefers-color-scheme: dark)' ? matches : false,
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    })),
  });
}

/**
 * The frontend package's index.html. Vitest transforms test files, so
 * import.meta.url is not a usable file URL here — resolve from the
 * working directory instead (npm test runs from the frontend package
 * root; a repo-root invocation is covered by the frontend/ candidate).
 */
function readIndexHtml(): string {
  const candidates = [join(process.cwd(), 'index.html'), join(process.cwd(), 'frontend', 'index.html')];
  for (const candidate of candidates) {
    if (existsSync(candidate)) return readFileSync(candidate, 'utf-8');
  }
  throw new Error('index.html not found from the vitest working directory');
}

/** The inline <script> block from the real index.html (not the module tag). */
function readInitScript(): string {
  const html = readIndexHtml();
  const match = html.match(/<script>([\s\S]*?)<\/script>/);
  expect(match, 'index.html must carry the inline theme init script').not.toBeNull();
  return match![1];
}

describe('theme resolution (backlog #227)', () => {
  beforeEach(() => {
    window.localStorage.clear();
    document.documentElement.classList.remove('dark');
    document.documentElement.style.colorScheme = '';
    useThemeStore.setState({ preference: 'system' });
    setSystemDark(false);
  });

  afterEach(() => {
    window.localStorage.clear();
    document.documentElement.classList.remove('dark');
    useThemeStore.setState({ preference: 'system' });
    vi.restoreAllMocks();
  });

  it('a stored preference beats the system media query', () => {
    expect(resolveTheme('dark', false)).toBe('dark');
    expect(resolveTheme('light', true)).toBe('light');
  });

  it('system follows the media query', () => {
    expect(resolveTheme('system', true)).toBe('dark');
    expect(resolveTheme('system', false)).toBe('light');
  });

  it('a corrupt stored value falls back to system, never crashes', () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, 'neon');
    expect(readStoredTheme()).toBe('system');
    expect(resolveTheme(readStoredTheme(), true)).toBe('dark');
  });

  it('applyTheme toggles the dark class and the color scheme', () => {
    expect(applyTheme('dark')).toBe('dark');
    expect(document.documentElement.classList.contains('dark')).toBe(true);
    expect(document.documentElement.style.colorScheme).toBe('dark');
    expect(applyTheme('light')).toBe('light');
    expect(document.documentElement.classList.contains('dark')).toBe(false);
  });

  it('the store round-trips: setPreference persists and repaints', () => {
    useThemeStore.getState().setPreference('dark');
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    expect(document.documentElement.classList.contains('dark')).toBe(true);
    expect(useThemeStore.getState().preference).toBe('dark');
    useThemeStore.getState().setPreference('light');
    expect(document.documentElement.classList.contains('dark')).toBe(false);
  });

  it('setStoredTheme tolerates a denied storage without breaking the theme', () => {
    const setSpy = vi.spyOn(window.Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied');
    });
    expect(setStoredTheme('dark')).toBe('dark');
    expect(document.documentElement.classList.contains('dark')).toBe(true);
    setSpy.mockRestore();
  });
});

describe('index.html pre-paint init script (backlog #227)', () => {
  beforeEach(() => {
    window.localStorage.clear();
    document.documentElement.classList.remove('dark');
  });

  afterEach(() => {
    window.localStorage.clear();
    document.documentElement.classList.remove('dark');
    vi.restoreAllMocks();
  });

  it('the inline script runs before the module bundle script tag', () => {
    const html = readIndexHtml();
    expect(html).toContain(THEME_STORAGE_KEY);
    expect(html).toContain('prefers-color-scheme: dark');
    expect(html.indexOf(THEME_STORAGE_KEY)).toBeLessThan(html.indexOf('/src/main.tsx'));
  });

  it('the shipped script itself resolves stored-beats-system', () => {
    const script = readInitScript();
    const run = () => new Function(script)();

    // Stored dark beats a light OS.
    setSystemDark(false);
    window.localStorage.setItem(THEME_STORAGE_KEY, 'dark');
    run();
    expect(document.documentElement.classList.contains('dark')).toBe(true);

    // Stored light beats a dark OS.
    setSystemDark(true);
    window.localStorage.setItem(THEME_STORAGE_KEY, 'light');
    run();
    expect(document.documentElement.classList.contains('dark')).toBe(false);

    // No stored value follows the OS.
    window.localStorage.removeItem(THEME_STORAGE_KEY);
    run();
    expect(document.documentElement.classList.contains('dark')).toBe(true);
    setSystemDark(false);
    run();
    expect(document.documentElement.classList.contains('dark')).toBe(false);
  });
});
