import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  LOCALE_STORAGE_KEY,
  LOCALES,
  MESSAGES,
  readStoredLocale,
  setStoredLocale,
  t,
} from './locale';
import { useLocaleStore } from './store/useLocaleStore';

/**
 * Locale contract (backlog #431).
 *
 * Three layers are pinned here:
 * 1. Persistence — the stored locale survives a reload (a fresh read
 *    sees what the last write stored); an unknown stored value falls
 *    back to 'en', never to a blank or a crash.
 * 2. Lookup — `t()` returns the English copy by default and the
 *    Chinese copy once zh-CN is stored; an explicit locale argument
 *    overrides storage; `{name}` placeholders fill in.
 * 3. Dictionary completeness — every key carries a non-empty string in
 *    both locales, so a page can never render a missing translation.
 * 4. Store round-trip — setting a locale persists it and flips the
 *    store state, which is what re-renders subscribed pages.
 */

describe('locale preference (backlog #431)', () => {
  beforeEach(() => {
    window.localStorage.clear();
    useLocaleStore.setState({ locale: 'en' });
  });

  afterEach(() => {
    window.localStorage.clear();
    useLocaleStore.setState({ locale: 'en' });
  });

  it('defaults to en when nothing is stored', () => {
    expect(readStoredLocale()).toBe('en');
    expect(t('order.notFound')).toBe('This order no longer exists.');
  });

  it('an unknown stored locale falls back to en', () => {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, 'de');
    expect(readStoredLocale()).toBe('en');
    expect(t('order.notFound')).toBe('This order no longer exists.');
  });

  it('a stored zh-CN choice survives a fresh read', () => {
    setStoredLocale('zh-CN');
    expect(window.localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('zh-CN');
    // A fresh read — what a reload sees — returns the stored choice.
    expect(readStoredLocale()).toBe('zh-CN');
    expect(t('order.notFound')).toBe('这笔订单已不存在。');
  });

  it('an explicit locale argument overrides storage', () => {
    setStoredLocale('zh-CN');
    expect(t('order.notFound', 'en')).toBe('This order no longer exists.');
    expect(t('order.notFound', 'zh-CN')).toBe('这笔订单已不存在。');
  });

  it('fills {name} placeholders', () => {
    expect(t('orderPage.statusLine', 'en', { id: 9, status: 'pending' })).toBe(
      'Order #9 is pending.',
    );
    expect(t('orderPage.statusLine', 'zh-CN', { id: 9, status: 'pending' })).toBe(
      '订单 #9，状态：pending。',
    );
  });

  it('every message key has a non-empty string in both locales', () => {
    for (const [key, entry] of Object.entries(MESSAGES)) {
      expect(entry.en.trim().length, `${key} en`).toBeGreaterThan(0);
      expect(entry['zh-CN'].trim().length, `${key} zh-CN`).toBeGreaterThan(0);
      // The two locales must not be the same string — a copy-paste
      // that forgot the translation would otherwise pass silently.
      expect(entry['zh-CN'], `${key} must differ from en`).not.toBe(entry.en);
    }
    // Both locales are offered by the toggle.
    expect([...LOCALES].sort()).toEqual(['en', 'zh-CN']);
  });

  it('the store round-trips: setLocale persists and updates state', () => {
    useLocaleStore.getState().setLocale('zh-CN');
    expect(window.localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('zh-CN');
    expect(useLocaleStore.getState().locale).toBe('zh-CN');
    expect(t('login.required')).toBe('请输入用户名或邮箱和密码。');

    useLocaleStore.getState().setLocale('en');
    expect(window.localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en');
    expect(useLocaleStore.getState().locale).toBe('en');
    expect(t('login.required')).toBe('Username or email and password are required.');
  });
});
