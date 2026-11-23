import { t, type Locale } from '../locale';
import { useLocaleStore } from '../store/useLocaleStore';

/**
 * Language toggle (backlog #431), rendered in the app nav so it is
 * reachable on every page — including /login, where a visitor picks a
 * language before ever signing in. Two locales only (en / zh-CN).
 *
 * The button shows the OTHER locale's name in its own script
 * ("中文" while English is active, "English" while Chinese is active),
 * so the label stays readable whichever language is active; the
 * accessible name and tooltip state the switch in the current locale.
 * The choice is persisted like the theme store (localStorage, no
 * backend state) and every subscribed page re-renders without a reload.
 */
export default function LocaleControl() {
  const locale = useLocaleStore((s) => s.locale);
  const setLocale = useLocaleStore((s) => s.setLocale);
  const next: Locale = locale === 'en' ? 'zh-CN' : 'en';

  return (
    <button
      type="button"
      onClick={() => setLocale(next)}
      aria-label={t('locale.switchLabel', locale)}
      title={t('locale.switchTitle', locale)}
      className="rounded-full border border-apple-hairline px-3 py-1.5 text-sm font-semibold text-neutral-600 transition hover:text-apple-ink dark:border-white/15 dark:text-neutral-300 dark:hover:text-white"
    >
      {next === 'zh-CN' ? '中文' : 'English'}
    </button>
  );
}
