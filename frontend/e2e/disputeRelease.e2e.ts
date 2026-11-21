import { expect, test, type APIRequestContext } from '@playwright/test';
import {
  createListingViaApi,
  expectOrderRowStatus,
  loginThroughUi,
  logoutThroughUi,
  newAccount,
  PASSWORD,
  registerViaApi,
  uniqueSuffix,
  type TestAccount,
} from './helpers';

/**
 * The dispute resolve-release journey (backlog #411) — the seventeenth
 * flow of the browser e2e smoke suite: a buyer disputes a PAID order,
 * the ADMIN resolves it by releasing the order (not refunding it), and
 * the buyer sees the order back at PAID in their own session — the
 * pre-dispute status restored, not a silent rewrite. The sixteen
 * earlier flows live in smoke.e2e.ts (seven), checkout.e2e.ts (cart
 * checkout), messaging.e2e.ts (buyer–seller messaging),
 * moderation.e2e.ts (listing report → admin action), favorites.e2e.ts
 * (favorites + seller-follow), savedSearches.e2e.ts (saved-searches),
 * mailedSale.e2e.ts (mailed sale), appeals.e2e.ts (seller appeal),
 * payouts.e2e.ts (seller payout) and passwordReset.e2e.ts
 * (password reset); the shared account/API/login moulds live in
 * helpers.ts. Selectors are role/label based, like the rest of the
 * suite, never CSS paths.
 *
 * What this flow proves end to end, in the browser, that no earlier
 * flow covers:
 * - the release resolution as a journey (#169/#255): flow 7 proves
 *   resolve-refund only, and refund and release are different money
 *   outcomes — a release must hand the order back to its pre-dispute
 *   status with no refund, so the buyer-side result is PAID, not
 *   REFUNDED;
 * - the release surface itself: the ADMIN finds the dispute in
 *   /admin/disputes, sees the stored previous status (PAID) on the
 *   row, types the required resolution note (#240 — the buttons stay
 *   disabled until one is typed) and clicks "Release order"; the row
 *   records RESOLVED_RELEASE with the note;
 * - the buyer-side result, pinned through the API at each step: the
 *   order reads DISPUTED while the dispute is open and PAID again
 *   after the release, and the buyer, in their own session, sees the
 *   PAID chip back on the row, the frozen note gone, and the
 *   "Report a problem" control offered again — so a green UI
 *   confirmation alone cannot pass this flow.
 *
 * What it does NOT cover, stated plainly: a release on a SHIPPED or
 * COMPLETED order (the same resolution restores SHIPPED/COMPLETED —
 * unit-covered in OrderDisputeTest, not driven here), the release on
 * an already-resolved dispute (422, unit-covered), the dispute-window
 * 422 on a stale order (unit-covered; this flow opens its dispute
 * seconds after pay, inside the 30 s e2e window, like flow 7), and
 * the seller-side dispute button (backlog #414 — this flow opens the
 * dispute from the buyer's My orders rows only).
 *
 * Credential-bucket arithmetic (this file's own runs; the bucket is
 * shared per IP with the smoke file, but the files run serially and
 * every login resets it): 2 registers + 3 logins, worst run between
 * resets is 3 of 5 —
 *   register seller + register buyer + buyer login = 3
 *   admin login (after the buyer login reset)     = 1
 *   buyer login (after the admin login reset)     = 1
 * Order create/pay calls carry a Bearer token and never touch the
 * credential bucket; the dispute open and the ADMIN resolution sit in
 * their own per-user buckets (backlog #282 / unthrottled), not the
 * credential one.
 *
 * Stack note: like the other sixteen flows, this spec runs in the
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
 * Creates an order through the public API (backlog #194 flow 5 seeds
 * its PAID order this way — the real endpoint, with the
 * Idempotency-Key header the endpoint requires, never a fixture or a
 * test-only back door). Local copy of smoke.e2e.ts's helper: the
 * helpers module holds only the moulds shared across specs, and the
 * order seeders stay with the specs that use them.
 */
async function createOrderViaApi(
  request: APIRequestContext,
  buyerToken: string,
  itemId: number,
): Promise<number> {
  const res = await request.post('/api/orders', {
    headers: {
      Authorization: `Bearer ${buyerToken}`,
      'Idempotency-Key': `e2e-${uniqueSuffix()}`,
    },
    data: { itemId },
  });
  expect(res.status(), 'API create order should succeed').toBe(200);
  const body = await res.json();
  return body.data.id as number;
}

/**
 * Pays an order through the public API. The SPA sends no payment
 * token, so the mock capture always succeeds here; the pay response
 * is the only place the handover code is ever disclosed (#179), but
 * this flow needs no code — the order is released, never completed.
 */
async function payOrderViaApi(
  request: APIRequestContext,
  buyerToken: string,
  orderId: number,
): Promise<void> {
  const res = await request.post(`/api/orders/${orderId}/pay`, {
    headers: { Authorization: `Bearer ${buyerToken}` },
    data: {},
  });
  expect(res.status(), 'API pay should succeed').toBe(200);
  const body = await res.json();
  expect(body.data.status, 'paid order carries PAID status').toBe('PAID');
}

/**
 * Reads one order back through the public API (backlog #213 flow 6
 * pins the created order's amount this way). Used here to pin the
 * order's status at each step — DISPUTED while the dispute is open,
 * PAID again after the release — so a green UI chip alone cannot
 * pass this flow.
 */
async function getOrderViaApi(
  request: APIRequestContext,
  buyerToken: string,
  orderId: number,
): Promise<{ status: string }> {
  const res = await request.get(`/api/orders/${orderId}`, {
    headers: { Authorization: `Bearer ${buyerToken}` },
  });
  expect(res.status(), 'API read order should succeed').toBe(200);
  const body = await res.json();
  return body.data as { status: string };
}

test('buyer disputes a paid order, admin releases it, and the buyer sees PAID again', async ({
  page,
  request,
}) => {
  const seller = newAccount('e2esell');
  const buyer = newAccount('e2ebuy');
  const sellerToken = await registerViaApi(request, seller);
  const buyerToken = await registerViaApi(request, buyer);
  const title = `E2E dispute-release textbook ${uniqueSuffix()}`;
  const itemId = await createListingViaApi(request, sellerToken, title);
  // Seed the PAID order through the real API (the flow 5 mould): what
  // this flow proves in the browser is the dispute, the ADMIN release
  // and the buyer-side result.
  const orderId = await createOrderViaApi(request, buyerToken, itemId);
  await payOrderViaApi(request, buyerToken, orderId);

  await loginThroughUi(page, buyer);

  // The buyer opens the dispute from My orders with a reason; the row
  // flips to DISPUTED and shows its frozen note.
  await page.getByRole('link', { name: 'My orders' }).click();
  await expect(page).toHaveURL('/orders');
  await expectOrderRowStatus(page, orderId, 'PAID');
  const buyerRow = page.locator('li', { hasText: `Order #${orderId}` });
  await buyerRow.getByRole('button', { name: 'Report a problem' }).click();
  await buyerRow.getByLabel('What went wrong?').fill('The parcel arrived empty — the textbook is missing.');
  await buyerRow.getByRole('button', { name: 'Open dispute' }).click();
  await expect(buyerRow).toContainText('Dispute open — this order is frozen');
  await expectOrderRowStatus(page, orderId, 'DISPUTED');

  // The API pin: the order is DISPUTED while the dispute is open.
  expect(
    (await getOrderViaApi(request, buyerToken, orderId)).status,
    'order is DISPUTED after the buyer opens the dispute',
  ).toBe('DISPUTED');

  // The seeded ADMIN releases the order from the admin queue: the row
  // carries the stored previous status (PAID) before the decision,
  // and records the release with the resolution note after it.
  await logoutThroughUi(page);
  await loginThroughUi(page, ADMIN_ACCOUNT);
  await page.getByRole('link', { name: 'Admin disputes' }).click();
  await expect(page).toHaveURL('/admin/disputes');
  await expect(page.getByRole('heading', { name: 'Admin disputes' })).toBeVisible();
  const disputeRow = page.locator('tr', { hasText: `Order #${orderId}` });
  await expect(disputeRow).toContainText('OPEN');
  await expect(disputeRow).toContainText('PAID');
  // Backlog #240: every resolution requires a note — the buttons stay
  // disabled until one is typed.
  await disputeRow.getByLabel('Resolution note').fill('Item was actually delivered.');
  await disputeRow.getByRole('button', { name: 'Release order' }).click();
  await expect(disputeRow).toContainText('RESOLVED_RELEASE');
  await expect(disputeRow).toContainText('Item was actually delivered.');

  // The API pin: the release restores the pre-dispute status — the
  // order reads PAID again, with no refund behind it.
  expect(
    (await getOrderViaApi(request, buyerToken, orderId)).status,
    'release restores the order to its pre-dispute PAID status',
  ).toBe('PAID');

  // The buyer, in their own session, sees the order back at PAID: the
  // chip reads PAID, the frozen note is gone, and the dispute control
  // is offered again — the order is live, not silently rewritten.
  await logoutThroughUi(page);
  await loginThroughUi(page, buyer);
  await page.getByRole('link', { name: 'My orders' }).click();
  await expect(page).toHaveURL('/orders');
  await expectOrderRowStatus(page, orderId, 'PAID');
  const buyerRowAfter = page.locator('li', { hasText: `Order #${orderId}` });
  await expect(buyerRowAfter).not.toContainText('Dispute open');
  await expect(
    buyerRowAfter.getByRole('button', { name: 'Report a problem' }),
  ).toBeVisible();
});
