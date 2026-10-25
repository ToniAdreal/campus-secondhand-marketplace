import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import ThemeControl from './ThemeControl';
import { THEME_STORAGE_KEY } from '../theme';
import { useThemeStore } from '../store/useThemeStore';

/**
 * Theme control round-trip (backlog #227): picking a radio persists the
 * preference and flips the `dark` class on <html> without a reload, and
 * `system` tracks the (stubbed) OS media query through the store.
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

describe('ThemeControl (backlog #227)', () => {
  beforeEach(() => {
    window.localStorage.clear();
    document.documentElement.classList.remove('dark');
    useThemeStore.setState({ preference: 'system' });
    setSystemDark(false);
  });

  afterEach(() => {
    cleanup();
    window.localStorage.clear();
    document.documentElement.classList.remove('dark');
    useThemeStore.setState({ preference: 'system' });
    vi.restoreAllMocks();
  });

  it('picking Dark persists the preference and adds the dark class', () => {
    render(<ThemeControl />);
    fireEvent.click(screen.getByRole('radio', { name: /dark/i }));
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    expect(document.documentElement.classList.contains('dark')).toBe(true);
    expect(useThemeStore.getState().preference).toBe('dark');
  });

  it('picking Light removes the dark class again', () => {
    render(<ThemeControl />);
    fireEvent.click(screen.getByRole('radio', { name: /dark/i }));
    expect(document.documentElement.classList.contains('dark')).toBe(true);
    fireEvent.click(screen.getByRole('radio', { name: /^light/i }));
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('light');
    expect(document.documentElement.classList.contains('dark')).toBe(false);
  });

  it('picking System follows the OS media query', () => {
    setSystemDark(true);
    // Start from Dark: clicking an already-checked System radio fires
    // no change event, so the round-trip needs a real change to test.
    useThemeStore.getState().setPreference('dark');
    render(<ThemeControl />);
    fireEvent.click(screen.getByRole('radio', { name: /system/i }));
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('system');
    expect(document.documentElement.classList.contains('dark')).toBe(true);
  });
});
