import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import {
  createListingViaApi,
  loginThroughUi,
  newAccount,
  registerViaApi,
  uniqueSuffix,
  type TestAccount,
} from './helpers';

/**
 * The color-contrast pass (backlog #369) — NOT a thirteenth flow (the
 * mailed-sale journey in #370 is the thirteenth): a real-Chromium axe
 * run with the `color-contrast` rule only, over nine key pages in both
 * themes (light + dark). The Vitest axe suite (#183) runs the same
 * pages in jsdom and cannot compute contrast — axe reports the rule
 * incomplete there (no canvas/layout) — so until this pass, contrast
 * was unaudited anywhere, in either theme, including dark mode (the
 * #227 note said so outright).
 *
 * What this pass proves, per page and per theme: zero `color-contrast`
 * violations. It runs `withRules(['color-contrast'])` — the scope is
 * the rule list itself, not a disabled rule: no rule is turned off to
 * get green, and the other axe rules stay covered by the jsdom suite,
 * which already asserts zero serious/critical violations over the
 * WCAG 2.0/2.1 A+AA tags. This is contrast only, not a full WCAG
 * audit — no keyboard walkthrough or screen-reader QA is claimed.
 *
 * First-run measurement: the first CI run of this file IS the
 * measurement. A genuine contrast failure is fixed in a follow-up
 * commit or filed as a follow-up backlog item — the rule is never
 * disabled and the page is never dropped from the list to get green.
 * `incomplete` results (elements axe cannot determine, e.g. text over
 * an image) are logged, not asserted: they are data for that
 * follow-up, not failures.
 *
 * Seeding mould (the flow-5 mould): every page gets its own seller
 * registered through the real API and, where the page needs content,
 * its own listing created through the real API — never a fixture.
 * Favorites and Notifications run against the fresh account's empty
 * states; populated-state contrast is a follow-up, stated here so
 * the empty-state coverage is not mistaken for more than it is.
 *
 * Credential-bucket arithmetic (the bucket is shared per IP with the
 * other six spec files, but the files run serially and every login
 * resets it): each test registers at most once and ends with the
 * bucket full — the guest-page tests log in at the end, the signed-in
 * tests log in before the checks and consume nothing after — so the
 * worst run between resets is 1 of 5.
 *
 * Stack note: like the twelve flows, this pass runs in the existing
 * CI `e2e` job (vite preview + backend jar on H2, picked up
 * automatically by the config's `**\/*.e2e.ts` match) — no new job,
 * no second stack.
 */

const THEMES = ['light', 'dark'] as const;

interface SeededListing {
  seller: TestAccount;
  title: string;
  itemId: number;
}

async function seedListing(request: APIRequestContext): Promise<SeededListing> {
  const seller = newAccount('e2ecc');
  const token = await registerViaApi(request, seller);
  const title = `E2E contrast oak chair ${uniqueSuffix()}`;
  const itemId = await createListingViaApi(request, token, title);
  return { seller, title, itemId };
}

async function registerSeller(request: APIRequestContext): Promise<TestAccount> {
  const seller = newAccount('e2ecc');
  await registerViaApi(request, seller);
  return seller;
}

/**
 * Runs axe with the color-contrast rule only over `url` in both
 * themes. The theme is written to the same localStorage key the
 * pre-paint script and main.tsx boot read (#227), then the page is
 * reloaded so the class on <html> is applied before first paint —
 * exactly what a user switching themes gets. `ready` re-asserts the
 * page's heading (and, where the page shows seeded content, the
 * seeded listing) after every navigation, so axe never runs against
 * a half-rendered page. The session cookie survives the reloads, so
 * signed-in pages stay signed in.
 */
async function checkContrast(
  page: Page,
  url: string,
  ready: () => Promise<void>,
): Promise<void> {
  await page.goto(url);
  await ready();
  for (const theme of THEMES) {
    await page.evaluate((t: string) => {
      window.localStorage.setItem('campus-marketplace-theme', t);
    }, theme);
    await page.reload();
    await ready();
    const results = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze();
    const detail = results.violations
      .map(
        (v) =>
          `${v.id} [${v.impact ?? 'no impact'}]: ${v.description}\n` +
          v.nodes
            .map((n) => `  - ${n.target.join(' ')}: ${n.failureSummary ?? ''}`)
            .join('\n'),
      )
      .join('\n');
    expect(
      results.violations,
      `color-contrast violations on ${url} (${theme} theme):\n${detail}`,
    ).toEqual([]);
    if (results.incomplete.length > 0) {
      console.log(
        `color-contrast incomplete on ${url} (${theme} theme): ` +
          `${results.incomplete.length} element(s) axe could not determine ` +
          `(${results.incomplete.map((v) => v.id).join(', ')}) — see the CI log, not a failure.`,
      );
    }
  }
}

test('home browse page has no color-contrast violations in either theme', async ({
  page,
  request,
}) => {
  const { seller, title } = await seedListing(request);
  await checkContrast(page, '/', async () => {
    await expect(page.getByRole('heading', { name: 'Latest listings' })).toBeVisible();
    await expect(page.getByRole('link', { name: title })).toBeVisible();
  });
  await loginThroughUi(page, seller);
});

test('item detail page has no color-contrast violations in either theme', async ({
  page,
  request,
}) => {
  const { seller, title, itemId } = await seedListing(request);
  await checkContrast(page, `/items/${itemId}`, async () => {
    await expect(page.getByRole('heading', { name: title })).toBeVisible();
  });
  await loginThroughUi(page, seller);
});

test('login page has no color-contrast violations in either theme', async ({
  page,
  request,
}) => {
  const seller = await registerSeller(request);
  await checkContrast(page, '/login', async () => {
    await expect(page.getByRole('heading', { name: 'Login' })).toBeVisible();
  });
  await loginThroughUi(page, seller);
});

test('register page has no color-contrast violations in either theme', async ({
  page,
  request,
}) => {
  const seller = await registerSeller(request);
  await checkContrast(page, '/register', async () => {
    await expect(page.getByRole('heading', { name: 'Register' })).toBeVisible();
  });
  await loginThroughUi(page, seller);
});

test('forgot-password page has no color-contrast violations in either theme', async ({
  page,
  request,
}) => {
  const seller = await registerSeller(request);
  await checkContrast(page, '/forgot-password', async () => {
    await expect(page.getByRole('heading', { name: 'Forgot password' })).toBeVisible();
  });
  await loginThroughUi(page, seller);
});

test('create-listing page has no color-contrast violations in either theme', async ({
  page,
  request,
}) => {
  const seller = await registerSeller(request);
  await loginThroughUi(page, seller);
  await checkContrast(page, '/items/new', async () => {
    await expect(page.getByRole('heading', { name: 'Sell an item' })).toBeVisible();
  });
});

test('settings page has no color-contrast violations in either theme', async ({
  page,
  request,
}) => {
  const seller = await registerSeller(request);
  await loginThroughUi(page, seller);
  await checkContrast(page, '/settings', async () => {
    await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
  });
});

test('favorites page has no color-contrast violations in either theme', async ({
  page,
  request,
}) => {
  const seller = await registerSeller(request);
  await loginThroughUi(page, seller);
  await checkContrast(page, '/favorites', async () => {
    await expect(page.getByRole('heading', { name: 'Favorites' })).toBeVisible();
  });
});

test('notifications page has no color-contrast violations in either theme', async ({
  page,
  request,
}) => {
  const seller = await registerSeller(request);
  await loginThroughUi(page, seller);
  await checkContrast(page, '/notifications', async () => {
    await expect(page.getByRole('heading', { name: 'Notifications' })).toBeVisible();
  });
});
