import { expect, test, type APIRequestContext } from '@playwright/test';
import {
  createListingViaApi,
  loginThroughUi,
  logoutThroughUi,
  newAccount,
  PASSWORD,
  registerViaApi,
  uniqueSuffix,
  type TestAccount,
} from './helpers';

/**
 * The listing-report → admin-action flow (backlog #346) — the tenth
 * flow of the browser e2e smoke suite: a buyer reports a listing from
 * its detail page, the seeded ADMIN sees the report in /admin/reports
 * and acts on it, and the listing detail then shows WITHDRAWN and stops
 * offering purchase — plus the negative case, where a dismissed report
 * leaves its listing AVAILABLE. The nine earlier flows live in
 * smoke.e2e.ts (seven), checkout.e2e.ts (cart checkout) and
 * messaging.e2e.ts (buyer–seller messaging); the shared
 * account/API/login moulds live in helpers.ts. Selectors are
 * role/label based, like the rest of the suite, never CSS paths.
 *
 * What this flow proves end to end, in the browser, that no earlier
 * flow covers:
 * - the report surface (#160): the buyer files from the detail-page
 *   dialog (reason + note), the dialog's success copy appears, and a
 *   repeat report through the public API returns the existing row —
 *   the backend's one-OPEN-report-per-(reporter, item) dedupe, not a
 *   second row in the ADMIN queue;
 * - the ADMIN moderation queue as a surface: the ADMIN opens
 *   /admin/reports from the nav, finds the report row by its unique
 *   note, and the action flips the row to ACTIONED while the listing
 *   flips to WITHDRAWN in the same transaction — then the buyer, in
 *   their own session, sees WITHDRAWN on the detail page and no
 *   "Buy now" button (canBuy is AVAILABLE-only), so the withdrawn
 *   listing stops being offered;
 * - the dismiss path: the second report's row flips to DISMISSED and
 *   its listing stays AVAILABLE with "Buy now" still offered.
 *
 * What it does NOT cover, stated plainly: the seller self-report 422
 * and the per-user report rate limit (429) stay covered by their own
 * MockMvc tests — this flow proves the happy path, the dedupe, the
 * action and the dismiss from the UI side. The listings are seeded
 * through the real API (register + create listing), never a fixture;
 * only the reporting, the ADMIN decision and the buyer-side result
 * are browser-driven, the flow-5 mould.
 *
 * Credential-bucket arithmetic (this file's own runs; the bucket is
 * shared per IP with the smoke, checkout and messaging files, but the
 * files run serially and every login resets it): 2 registers + 3
 * logins, worst run between resets is 3 of 5 —
 *   register seller + register buyer + buyer login = 3
 *   admin login (after the buyer login reset)     = 1
 *   buyer login (after the admin login reset)     = 1
 * The API report posts carry a Bearer token and never touch the
 * credential bucket; the ADMIN dismiss/action writes sit in their own
 * per-user bucket (backlog #304), not the credential one.
 *
 * Stack note: like the other nine flows, this spec runs in the
 * existing CI `e2e` job (vite preview + backend jar on H2, picked up
 * automatically by the config's `**\/*.e2e.ts` match) — no new job,
 * no second stack. The docker-compose job stays curl-only.
 */

/**
 * The seeded ADMIN account (backlog #213, flow 7 in smoke.e2e.ts).
 * These credentials are NOT registered through the API —
 * playwright.config.ts passes the same values to the backend as
 * APP_BOOTSTRAP_ADMIN_* and BootstrapAdminInitializer creates the
 * account at boot. They exist only inside the throwaway per-job e2e
 * stack (in-memory H2, CI-only browsers); the password is the suite's
 * shared test password.
 */
const ADMIN_ACCOUNT: TestAccount = {
  username: 'e2eadmin',
  email: 'e2eadmin@example.com',
  password: PASSWORD,
};

/**
 * Files a report through the public API and returns the report id.
 * Used here only to pin the dedupe contract: a repeat call while the
 * caller's earlier report is still OPEN returns the existing row, so
 * two calls return the same id.
 */
async function reportViaApi(
  request: APIRequestContext,
  token: string,
  itemId: number,
  reason: string,
  note: string,
): Promise<number> {
  const res = await request.post(`/api/items/${itemId}/report`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { reason, note },
  });
  expect(res.status(), 'API report should succeed').toBe(200);
  const body = await res.json();
  return body.data.id as number;
}

test('buyer reports a listing, admin acts on it, and the detail shows WITHDRAWN — a dismissed report leaves its listing AVAILABLE', async ({
  page,
  request,
}) => {
  const seller = newAccount('e2esell');
  const buyer = newAccount('e2ebuy');
  const sellerToken = await registerViaApi(request, seller);
  const buyerToken = await registerViaApi(request, buyer);
  const titleOne = `E2E report textbook ${uniqueSuffix()}`;
  const titleTwo = `E2E dismiss textbook ${uniqueSuffix()}`;
  const itemOne = await createListingViaApi(request, sellerToken, titleOne);
  const itemTwo = await createListingViaApi(request, sellerToken, titleTwo);
  const noteOne = `Scam listing, copied photos ${uniqueSuffix()}`;
  const noteTwo = `Looks off but probably fine ${uniqueSuffix()}`;

  await loginThroughUi(page, buyer);

  // The buyer reports the first listing from its detail page; the
  // dialog's success copy confirms the filing.
  const cardOne = page.getByRole('link', { name: titleOne });
  await expect(cardOne).toBeVisible();
  await cardOne.click();
  await expect(page).toHaveURL(new RegExp(`/items/${itemOne}$`));
  await page.getByRole('button', { name: 'Report this listing' }).click();
  await page.getByLabel('Reason').selectOption('SCAM');
  await page.getByLabel('Details (optional)').fill(noteOne);
  await page.getByRole('button', { name: 'Submit report' }).click();
  await expect(
    page.getByText(
      'Report submitted — a moderator will review this listing. It stays visible until a moderator acts on it.',
    ),
  ).toBeVisible();

  // The dedupe contract through the public API: two repeat reports
  // return the existing row, never a second one.
  const firstRepeat = await reportViaApi(request, buyerToken, itemOne, 'SCAM', noteOne);
  const secondRepeat = await reportViaApi(request, buyerToken, itemOne, 'SCAM', noteOne);
  expect(secondRepeat, 'repeat report returns the existing row').toBe(firstRepeat);

  // The buyer reports the second listing too, so the negative case
  // has its own row in the same queue.
  await page.goto('/');
  const cardTwo = page.getByRole('link', { name: titleTwo });
  await expect(cardTwo).toBeVisible();
  await cardTwo.click();
  await expect(page).toHaveURL(new RegExp(`/items/${itemTwo}$`));
  await page.getByRole('button', { name: 'Report this listing' }).click();
  await page.getByLabel('Reason').selectOption('PROHIBITED');
  await page.getByLabel('Details (optional)').fill(noteTwo);
  await page.getByRole('button', { name: 'Submit report' }).click();
  await expect(
    page.getByText(
      'Report submitted — a moderator will review this listing. It stays visible until a moderator acts on it.',
    ),
  ).toBeVisible();

  // The seeded ADMIN opens the moderation queue from the nav and
  // acts on the first report; the row flips to ACTIONED.
  await logoutThroughUi(page);
  await loginThroughUi(page, ADMIN_ACCOUNT);
  await page.getByRole('link', { name: 'Admin reports' }).click();
  await expect(page).toHaveURL('/admin/reports');
  await expect(page.getByRole('heading', { name: 'Admin reports' })).toBeVisible();
  const actionRow = page.locator('tr', { hasText: noteOne });
  await expect(actionRow).toContainText('OPEN');
  await expect(actionRow).toContainText('SCAM');
  await actionRow.getByRole('button', { name: 'Action (withdraw listing)' }).click();
  await expect(actionRow).toContainText('ACTIONED');

  // The negative case: dismissing the second report resolves it
  // without touching the listing.
  const dismissRow = page.locator('tr', { hasText: noteTwo });
  await expect(dismissRow).toContainText('OPEN');
  await expect(dismissRow).toContainText('PROHIBITED');
  await dismissRow.getByRole('button', { name: 'Dismiss' }).click();
  await expect(dismissRow).toContainText('DISMISSED');

  // The buyer, in their own session, sees the acted-on listing as
  // WITHDRAWN with no purchase offered — the card still shows on
  // browse (the list carries no status filter), but its detail page
  // carries the WITHDRAWN badge and no "Buy now" button.
  await logoutThroughUi(page);
  await loginThroughUi(page, buyer);
  await page.goto('/');
  const cardOneAgain = page.getByRole('link', { name: titleOne });
  await expect(cardOneAgain).toBeVisible();
  await cardOneAgain.click();
  await expect(page).toHaveURL(new RegExp(`/items/${itemOne}$`));
  await expect(page.getByText('WITHDRAWN')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Buy now' })).toHaveCount(0);

  // And the dismissed report's listing is still AVAILABLE, still
  // buyable.
  await page.goto('/');
  const cardTwoAgain = page.getByRole('link', { name: titleTwo });
  await expect(cardTwoAgain).toBeVisible();
  await cardTwoAgain.click();
  await expect(page).toHaveURL(new RegExp(`/items/${itemTwo}$`));
  await expect(page.getByText('AVAILABLE')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Buy now' })).toBeVisible();
});
