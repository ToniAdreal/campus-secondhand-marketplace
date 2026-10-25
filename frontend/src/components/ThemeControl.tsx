import { useThemeStore } from '../store/useThemeStore';
import type { ThemePreference } from '../theme';

/**
 * Theme picker (backlog #227), rendered in Settings. Three radios, not a
 * binary toggle: `system` is a real third state (follow the OS), and it
 * is the default for visitors who never touched the control. The labels
 * are the control's accessible names, so the choice survives a screen
 * reader and the RTL round-trip test.
 *
 * The control only writes the preference — `useThemeStore.setPreference`
 * persists it and applies the `dark` class immediately, so the page
 * repaints in the new theme without a reload.
 */

const OPTIONS: { value: ThemePreference; label: string; hint: string }[] = [
  { value: 'light', label: 'Light', hint: 'Always use the light palette' },
  { value: 'dark', label: 'Dark', hint: 'Always use the dark palette' },
  { value: 'system', label: 'System', hint: 'Follow your device setting' },
];

export default function ThemeControl() {
  const preference = useThemeStore((s) => s.preference);
  const setPreference = useThemeStore((s) => s.setPreference);

  return (
    <fieldset>
      <legend className="mb-1 text-sm font-semibold">Theme</legend>
      <p className="mb-3 text-sm text-neutral-500 dark:text-neutral-400">
        Pick how the marketplace looks. System follows your device setting.
      </p>
      <div className="space-y-2">
        {OPTIONS.map((option) => (
          <label key={option.value} className="flex items-start gap-2 text-sm">
            <input
              type="radio"
              name="theme-preference"
              value={option.value}
              checked={preference === option.value}
              onChange={() => setPreference(option.value)}
              className="mt-0.5"
            />
            <span>
              <span className="block font-medium">{option.label}</span>
              <span className="block text-neutral-500 dark:text-neutral-400">{option.hint}</span>
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}
