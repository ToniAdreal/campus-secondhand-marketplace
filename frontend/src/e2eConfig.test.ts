import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Playwright e2e backend-env contract (backlog #406).
 *
 * The e2e stack's payout seam lives in `frontend/playwright.config.ts`:
 * a 10-second sweep cron and a 30-second dispute window, so a freshly
 * completed order's payout can reach PAYABLE inside a 60-second test.
 * These knobs exist only in that throwaway stack — production keeps the
 * hourly sweep and the 7-day window. This test reads the actual config
 * file (the theme.test.ts precedent) so the knobs can never drift out
 * of the config without a test failing.
 */
function readPlaywrightConfig(): string {
  const candidates = [
    join(process.cwd(), 'playwright.config.ts'),
    join(process.cwd(), 'frontend', 'playwright.config.ts'),
  ];
  const path = candidates.find((candidate) => existsSync(candidate));
  expect(path).toBeDefined();
  return readFileSync(path!, 'utf8');
}

describe('e2e backend env (playwright.config.ts)', () => {
  it('sets a 10-second payout sweep cron for the e2e stack', () => {
    expect(readPlaywrightConfig()).toContain(
      "APP_PAYOUTS_SWEEP_CRON: '*/10 * * * * *'",
    );
  });

  it('sets a 30-second dispute window for the e2e stack', () => {
    expect(readPlaywrightConfig()).toContain("APP_DISPUTES_WINDOW: 'PT30S'");
  });
});
