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
 * The seller-appeal journey (backlog #394) — the fourteenth flow of
 * the browser e2e smoke suite: a buyer reports a listing, the ADMIN
 * withdraws it, the seller appeals, and the ADMIN upholds or rejects
 * the appeal on AdminAppealsPage. The thirteen earlier flows live in
 * smoke.e2e.ts (seven), checkout.e2e.ts (cart checkout),
 * messaging.e2e.ts (buyer–seller messaging), moderation.e2e.ts
 * (listing report → admin action), favorites.e2e.ts (favorites +
 * seller-follow), savedSearches.e2e.ts (saved-searches) and
 * mailedSale.e2e.ts (mailed sale); the shared account/API/login
 * moulds live in helpers.ts. Selectors are role/label based, like the
 * rest of the suite, never CSS paths.
 *
 * What this flow proves end to end, in the browser, that no earlier
 * flow covers:
 * - the appeal surface (#190): the seller appeals an ACTIONED report
 *   from the withdrawn listing's card on My listings (reason +
 *   "Appeal withdrawal"), and the card flips to the pending state
 *   ("Appeal pending — an admin is reviewing this withdrawal.");
 * - the ADMIN appeal queue as a surface: the ADMIN opens
 *   /admin/appeals from the nav, finds the appeal row by its unique
 *   reason, and upholding it flips the row to UPHELD with the
 *   outcome "Restored to AVAILABLE" while the listing returns to
 *   AVAILABLE in the same transaction — then the buyer, in their own
 *   session, sees AVAILABLE on the detail page and "Buy now" offered
 *   again, so the restored listing is buyable;
 * - the reject path: the second appeal's row flips to REJECTED with
 *   the outcome "Left as it was" and its listing stays WITHDRAWN
 *   with no "Buy now" button — the withdrawal stands.
 *
 * Wording note: the backlog item says the seller "appeals from the
 * listing" — the actual surface is the seller's My listings card
 * (backlog #190), not the detail page, which carries no appeal
 * control; the flow drives the real surface and says so.
 *
 * What it does NOT cover, stated plainly: the re-appeal 422 (a
 * resolved report cannot be appealed again), the appeal on an
 * OPEN/DISMISSED report 422, and the uphold-when-the-listing-moved-on
 * path (listingRestored=false) stay covered by their own unit tests
 * — this flow proves the happy path, the uphold, and the reject from
 * the UI side. The listings are seeded through the real API (register
 * + create listing), never a fixture; only the reporting, the ADMIN
 * decision, the appeal and the buyer-side result are browser-driven,
 * the flow-5 mould.
 *
 * Credential-bucket arithmetic (this file's own runs; the bucket is
 * shared per IP with the smoke, checkout and messaging files, but the
 * files run serially and every login resets it): 2 registers + 5
 * logins, worst run between resets is 3 of 5 —
 *   register seller + register buyer + buyer login = 3
 *   admin login (after the buyer login reset)     = 1
 *   seller login (after the admin login reset)    = 1
 *   admin login (after the seller login reset)    = 1
 *   buyer login (after the admin login reset)     = 1
 * The API report/appeal pins carry a Bearer token and never touch the
 * credential bucket; the ADMIN withdraw/appeal writes sit in their own
 * per-user bucket (backlog #304), not the credential one.
 *
 * Stack note: like the other thirteen flows, this spec runs in the
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

/** One row of GET /api/seller/reports, the fields this flow pins. */
interface SellerReportRow {
  id: number;
  itemId: number;
  appealStatus: 'OPEN' | 'UPHELD' | 'REJECTED' | null;
}

/**
 * Reads the seller's reports through the public API and returns the
 * row for one listing. Used to pin the appeal state at each step —
 * OPEN after filing, UPHELD/REJECTED after the ADMIN resolves.
 */
async function sellerReportForItem(
  request: APIRequestContext,
  token: string,
  itemId: number,
): Promise<SellerReportRow> {
  const res = await request.get('/api/seller/reports?page=0&size=100', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.status(), 'API seller reports should succeed').toBe(200);
  const body = await res.json();
  const row = (body.data.content as SellerReportRow[]).find((r) => r.itemId === itemId);
  expect(row, `seller reports should carry a row for item ${itemId}`).toBeTruthy();
  return row as SellerReportRow;
}

/** Reads one listing's status through the public API. */
async function itemStatus(
  request: APIRequestContext,
  token: string,
  itemId: number,
): Promise<string> {
  const res = await request.get(`/api/items/${itemId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.status(), 'API item read should succeed').toBe(200);
  const body = await res.json();
  return body.data.status as string;
}

test('buyer reports, admin withdraws, seller appeals, admin upholds — a rejected appeal leaves its listing WITHDRAWN', async ({
  page,
  request,
}) => {
  const seller = newAccount('e2esell');
  const buyer = newAccount('e2ebuy');
  const sellerToken = await registerViaApi(request, seller);
  const buyerToken = await registerViaApi(request, buyer);
  const titleOne = `E2E appeal textbook ${uniqueSuffix()}`;
  const titleTwo = `E2E reject textbook ${uniqueSuffix()}`;
  const itemOne = await createListingViaApi(request, sellerToken, titleOne);
  const itemTwo = await createListingViaApi(request, sellerToken, titleTwo);
  const noteOne = `Scam listing, copied photos ${uniqueSuffix()}`;
  const noteTwo = `Looks off but probably fine ${uniqueSuffix()}`;
  const appealOne = `Photos are my own, taken in my dorm ${uniqueSuffix()}`;
  const appealTwo = `This is a genuine second-hand sale ${uniqueSuffix()}`;

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

  // The buyer reports the second listing too, so the reject case has
  // its own row in the same queue.
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
  // withdraws both listings; both rows flip to ACTIONED.
  await logoutThroughUi(page);
  await loginThroughUi(page, ADMIN_ACCOUNT);
  await page.getByRole('link', { name: 'Admin reports' }).click();
  await expect(page).toHaveURL('/admin/reports');
  await expect(page.getByRole('heading', { name: 'Admin reports' })).toBeVisible();
  const actionRowOne = page.locator('tr', { hasText: noteOne });
  await expect(actionRowOne).toContainText('OPEN');
  await actionRowOne.getByRole('button', { name: 'Action (withdraw listing)' }).click();
  await expect(actionRowOne).toContainText('ACTIONED');
  const actionRowTwo = page.locator('tr', { hasText: noteTwo });
  await expect(actionRowTwo).toContainText('OPEN');
  await actionRowTwo.getByRole('button', { name: 'Action (withdraw listing)' }).click();
  await expect(actionRowTwo).toContainText('ACTIONED');

  // The seller appeals both withdrawals from their My listings cards;
  // each card flips to the pending state.
  await logoutThroughUi(page);
  await loginThroughUi(page, seller);
  await page.getByRole('link', { name: 'My listings' }).click();
  await expect(page).toHaveURL('/my-listings');
  await expect(page.getByRole('heading', { name: 'My listings' })).toBeVisible();
  const sellerCardOne = page.locator('li', { hasText: titleOne });
  await expect(sellerCardOne).toContainText(
    'This listing was withdrawn after a report was actioned.',
  );
  await sellerCardOne.getByLabel('Appeal reason').fill(appealOne);
  await sellerCardOne.getByRole('button', { name: 'Appeal withdrawal' }).click();
  await expect(sellerCardOne).toContainText(
    'Appeal pending — an admin is reviewing this withdrawal.',
  );
  const sellerCardTwo = page.locator('li', { hasText: titleTwo });
  await expect(sellerCardTwo).toContainText(
    'This listing was withdrawn after a report was actioned.',
  );
  await sellerCardTwo.getByLabel('Appeal reason').fill(appealTwo);
  await sellerCardTwo.getByRole('button', { name: 'Appeal withdrawal' }).click();
  await expect(sellerCardTwo).toContainText(
    'Appeal pending — an admin is reviewing this withdrawal.',
  );

  // The API pin: both appeals are OPEN on the seller's reports.
  expect(
    (await sellerReportForItem(request, sellerToken, itemOne)).appealStatus,
    'first appeal is OPEN after filing',
  ).toBe('OPEN');
  expect(
    (await sellerReportForItem(request, sellerToken, itemTwo)).appealStatus,
    'second appeal is OPEN after filing',
  ).toBe('OPEN');

  // The seeded ADMIN opens the appeal queue and upholds the first
  // appeal; the row flips to UPHELD and records the restore.
  await logoutThroughUi(page);
  await loginThroughUi(page, ADMIN_ACCOUNT);
  await page.getByRole('link', { name: 'Admin appeals' }).click();
  await expect(page).toHaveURL('/admin/appeals');
  await expect(page.getByRole('heading', { name: 'Admin appeals' })).toBeVisible();
  const appealRowOne = page.locator('tr', { hasText: appealOne });
  await expect(appealRowOne).toContainText('OPEN');
  await appealRowOne.getByRole('button', { name: 'Uphold appeal' }).click();
  await expect(appealRowOne).toContainText('UPHELD');
  await expect(appealRowOne).toContainText('Restored to AVAILABLE');

  // The negative case: rejecting the second appeal resolves it
  // without restoring the listing.
  const appealRowTwo = page.locator('tr', { hasText: appealTwo });
  await expect(appealRowTwo).toContainText('OPEN');
  await appealRowTwo.getByRole('button', { name: 'Reject appeal' }).click();
  await expect(appealRowTwo).toContainText('REJECTED');
  await expect(appealRowTwo).toContainText('Left as it was');

  // The API pins: the first listing is AVAILABLE again with its
  // appeal UPHELD, the second stays WITHDRAWN with its appeal
  // REJECTED — not just a UI status change on the queue rows.
  expect(
    await itemStatus(request, buyerToken, itemOne),
    'upheld listing returns to AVAILABLE',
  ).toBe('AVAILABLE');
  expect(
    (await sellerReportForItem(request, sellerToken, itemOne)).appealStatus,
    'first appeal is UPHELD after resolution',
  ).toBe('UPHELD');
  expect(
    await itemStatus(request, buyerToken, itemTwo),
    'rejected listing stays WITHDRAWN',
  ).toBe('WITHDRAWN');
  expect(
    (await sellerReportForItem(request, sellerToken, itemTwo)).appealStatus,
    'second appeal is REJECTED after resolution',
  ).toBe('REJECTED');

  // The buyer, in their own session, sees the upheld listing as
  // AVAILABLE with "Buy now" offered again — and the rejected
  // appeal's listing still WITHDRAWN with no purchase offered.
  await logoutThroughUi(page);
  await loginThroughUi(page, buyer);
  await page.goto('/');
  const cardOneAgain = page.getByRole('link', { name: titleOne });
  await expect(cardOneAgain).toBeVisible();
  await cardOneAgain.click();
  await expect(page).toHaveURL(new RegExp(`/items/${itemOne}$`));
  await expect(page.getByText('AVAILABLE')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Buy now' })).toBeVisible();
  await page.goto('/');
  const cardTwoAgain = page.getByRole('link', { name: titleTwo });
  await expect(cardTwoAgain).toBeVisible();
  await cardTwoAgain.click();
  await expect(page).toHaveURL(new RegExp(`/items/${itemTwo}$`));
  await expect(page.getByText('WITHDRAWN')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Buy now' })).toHaveCount(0);
});
