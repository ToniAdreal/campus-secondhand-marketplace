import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import LocaleControl from './LocaleControl';
import { LOCALE_STORAGE_KEY } from '../locale';
import { useLocaleStore } from '../store/useLocaleStore';

/**
 * Language toggle (backlog #431): the button shows the other locale's
 * name in its own script, clicking it flips the stored locale and the
 * store state, and the accessible name states the switch in the
 * current locale.
 */
describe('LocaleControl', () => {
  beforeEach(() => {
    window.localStorage.clear();
    useLocaleStore.setState({ locale: 'en' });
  });

  afterEach(() => {
    cleanup();
    window.localStorage.clear();
    useLocaleStore.setState({ locale: 'en' });
  });

  it('shows 中文 while English is active and flips to zh-CN on click', () => {
    render(<LocaleControl />);

    const button = screen.getByRole('button', { name: 'Switch language to Chinese' });
    expect(button.textContent).toBe('中文');

    fireEvent.click(button);

    expect(window.localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('zh-CN');
    expect(useLocaleStore.getState().locale).toBe('zh-CN');
    // The label now offers the way back, in its own script.
    expect(screen.getByRole('button', { name: '切换语言为英文' }).textContent).toBe(
      'English',
    );
  });

  it('flips back to en on a second click', () => {
    useLocaleStore.getState().setLocale('zh-CN');
    render(<LocaleControl />);

    fireEvent.click(screen.getByRole('button', { name: '切换语言为英文' }));

    expect(window.localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en');
    expect(useLocaleStore.getState().locale).toBe('en');
    expect(screen.getByRole('button', { name: 'Switch language to Chinese' }).textContent).toBe(
      '中文',
    );
  });
});
