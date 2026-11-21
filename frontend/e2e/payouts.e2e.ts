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
 * The seller-payout journey (backlog #398) — the fifteenth flow of
 * the browser e2e smoke suite: a buyer buys and pays, the seller
 * completes with the handover code, the seller watches the payout row
 * go PENDING → PAYABLE on /seller/payouts, the ADMIN marks it PAID on
 * the admin payments page, and the seller's totals reflect it. The
 * fourteen earlier flows live in smoke.e2e.ts (seven),
 * checkout.e2e.ts (cart checkout), messaging.e2e.ts (buyer–seller
 * messaging), moderation.e2e.ts (listing report → admin action),
 * favorites.e2e.ts (favorites + seller-follow),
 * savedSearches.e2e.ts (saved-searches), mailedSale.e2e.ts (mailed
 * sale) and appeals.e2e.ts (seller appeal); the shared account/API/
 * login moulds live in helpers.ts. Selectors are role/label based,
 * like the rest of the suite, never CSS paths.
 *
 * What this flow proves end to end, in the browser, that no earlier
 * flow covers:
 * - the payout ledger as a journey (#221/#341): completing an order
 *   creates the seller's settlement row (PENDING, ¥19.99 — the order
 *   amount, no adjustments), and /seller/payouts shows the row plus
 *   the totals line the seller actually reads;
 * - the promotion itself (#406, step A1): the row flips to PAYABLE
 *   only once completedAt + the dispute window has passed and the
 *   sweep has run — the flow waits on the real sweep, it does not
 *   fake the state;
 * - the ADMIN mark-paid UI (#405, step A2): the operator finds the
 *   row in the payout ledger on /admin/payments, confirms the native
 *   dialog, and the row settles by hand outside the sweep;
 * - the seller-side result: after the mark-paid, the seller's totals
 *   read 1 paid (¥19.99) with 0 pending and 0 payable, and the row
 *   itself reads PAID — pinned through the API at each step, so a
 *   green UI confirmation alone cannot pass this flow.
 *
 * What it does NOT cover, stated plainly: the hourly sweep paying a
 * row on its own (the ADMIN settles this row by hand, so the sweep's
 * pay path stays unit-covered); a held seller or an open dispute
 * blocking promotion (#341's skip/held outcomes); a dispute refund
 * after completion showing up as a negative adjustment (#221); and
 * the mark-paid 422 on a still-PENDING row (unit-covered). The
 * listing is seeded through the real API (register + create
 * listing), never a fixture; only the buy/pay/complete/mark-paid
 * transitions themselves are browser-driven, the flow-4 mould.
 *
 * Timeout note: this spec raises its own timeout to 120 s
 * (`test.setTimeout` below) — the only spec that does. The promotion
 * wait alone is 30 s of dispute window plus up to 10 s of sweep
 * cadence after completion (the #406 knobs), so the journey's floor
 * is ~40 s after the order completes and the suite's 60 s default
 * cannot hold the whole flow with the ADMIN and seller tails. The
 * window and cadence stay as #406 set them — shortening the window
 * further would squeeze the #213 dispute flow — and retries stay 0.
 *
 * Credential-bucket arithmetic (this file's own runs; the bucket is
 * shared per IP with the smoke file, but the files run serially and
 * every login resets it): 2 registers + 4 logins, worst run between
 * resets is 3 of 5 —
 *   register seller + register buyer + buyer login = 3
 *   seller login (after the buyer login reset)     = 1
 *   admin login (after the seller login reset)     = 1
 *   seller login (after the admin login reset)     = 1
 * Order create/pay/complete calls carry a Bearer token and never
 * touch the credential bucket; the ADMIN mark-paid write carries a
 * Bearer token too and never touches the credential bucket either
 * (it has no rate-limit bucket of its own — no matcher for
 * /api/admin/payouts in AuthRateLimitFilter).
 *
 * Stack note: like the other fourteen flows, this spec runs in the
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

/** One row of GET /api/seller/payouts, the fields this flow pins. */
interface SellerPayoutRow {
  id: number;
  orderId: number;
  status: 'PENDING' | 'PAYABLE' | 'PAID';
  netCents: number;
}

/** GET /api/seller/payouts/totals, the fields this flow pins. */
interface SellerPayoutTotals {
  pendingCount: number;
  pendingCents: number;
  payableCount: number;
  payableCents: number;
  paidCount: number;
  paidCents: number;
}

/**
 * Reads the seller's payout rows through the public API and returns
 * the row for one order. Used to pin the payout state at each step —
 * PENDING after completion, PAYABLE after the sweep promotes it,
 * PAID after the ADMIN marks it paid.
 */
async function sellerPayoutForOrder(
  request: APIRequestContext,
  token: string,
  orderId: number,
): Promise<SellerPayoutRow> {
  const res = await request.get('/api/seller/payouts?page=0&size=100', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.status(), 'API seller payouts should succeed').toBe(200);
  const body = await res.json();
  const row = (body.data.content as SellerPayoutRow[]).find((r) => r.orderId === orderId);
  expect(row, `seller payouts should carry a row for order ${orderId}`).toBeTruthy();
  return row as SellerPayoutRow;
}

/** Reads the seller's payout totals through the public API. */
async function sellerPayoutTotals(
  request: APIRequestContext,
  token: string,
): Promise<SellerPayoutTotals> {
  const res = await request.get('/api/seller/payouts/totals', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.status(), 'API seller payout totals should succeed').toBe(200);
  const body = await res.json();
  return body.data as SellerPayoutTotals;
}

test('seller payout goes PENDING to PAYABLE, admin marks it paid, seller totals reflect it', async ({
  page,
  request,
}) => {
  // The promotion wait (30 s window + up to 10 s sweep cadence after
  // completion) plus the journey steps cannot fit the suite's 60 s
  // default — see the header note.
  test.setTimeout(120_000);

  const seller = newAccount('e2esell');
  const buyer = newAccount('e2ebuy');
  const sellerToken = await registerViaApi(request, seller);
  const buyerToken = await registerViaApi(request, buyer);
  const title = `E2E payout textbook ${uniqueSuffix()}`;
  const itemId = await createListingViaApi(request, sellerToken, title);

  await loginThroughUi(page, buyer);

  // Browse → detail, like a real buyer (the card link, not a typed URL).
  const cardLink = page.getByRole('link', { name: title });
  await expect(cardLink).toBeVisible();
  await cardLink.click();
  await expect(page).toHaveURL(new RegExp(`/items/${itemId}$`));
  await expect(page.getByRole('heading', { name: title })).toBeVisible();

  // Buy now lands on the confirmation page for the new order.
  await page.getByRole('button', { name: 'Buy now' }).click();
  await expect(page).toHaveURL(/\/orders\/\d+$/);
  await expect(page.getByRole('heading', { name: 'Order confirmed' })).toBeVisible();
  const orderId = Number(page.url().split('/').pop());

  // Paying discloses the handover code exactly once, on this page (#179).
  await page.getByRole('button', { name: 'Pay now (demo)' }).click();
  const codeValue = page
    .locator('p', { hasText: 'Your handover code:' })
    .locator('strong');
  await expect(codeValue).toBeVisible();
  const handoverCode = (await codeValue.textContent())?.trim();
  expect(handoverCode, 'pay discloses a 6-digit handover code').toMatch(/^\d{6}$/);
  await expect(page.getByText('Paid (demo capture)')).toBeVisible();

  // The seller completes with the buyer's code on the Sales page; the
  // row flips to COMPLETED and the payout row is created in the same
  // transaction as the completion.
  await logoutThroughUi(page);
  await loginThroughUi(page, seller);
  await page.getByRole('link', { name: 'Sales' }).click();
  await expect(page).toHaveURL('/seller/orders');
  await expect(page.getByRole('heading', { name: 'Orders on my listings' })).toBeVisible();
  await expectOrderRowStatus(page, orderId, 'PAID');
  const salesRow = page.locator('li', { hasText: `Order #${orderId}` });
  await salesRow.getByLabel(/Handover code/).fill(handoverCode as string);
  await salesRow.getByRole('button', { name: 'Complete order' }).click();
  await expectOrderRowStatus(page, orderId, 'COMPLETED');

  // The seller's payout page shows the fresh row as PENDING with the
  // totals line reading 1 pending (¥19.99) — the settlement is the
  // order amount, no adjustments.
  await page.getByRole('link', { name: 'Payouts' }).click();
  await expect(page).toHaveURL('/seller/payouts');
  await expect(page.getByRole('heading', { name: 'Payouts' })).toBeVisible();
  await expect(page.getByText('1 pending (¥19.99)')).toBeVisible();
  const payoutRow = page.locator('li', { hasText: `Payout for order #${orderId}` });
  await expect(payoutRow).toContainText('PENDING');

  // The API pin: the row is PENDING at ¥19.99 net, and the totals agree.
  const pendingRow = await sellerPayoutForOrder(request, sellerToken, orderId);
  expect(pendingRow.status, 'payout row is PENDING after completion').toBe('PENDING');
  expect(pendingRow.netCents, 'payout net is the order amount').toBe(1999);
  const pendingTotals = await sellerPayoutTotals(request, sellerToken);
  expect(pendingTotals.pendingCount, 'totals carry 1 pending payout').toBe(1);
  expect(pendingTotals.pendingCents, 'pending total is the order amount').toBe(1999);
  expect(pendingTotals.payableCount, 'nothing payable yet').toBe(0);
  expect(pendingTotals.paidCount, 'nothing paid yet').toBe(0);

  // The promotion: the row flips to PAYABLE only once completedAt +
  // the 30 s dispute window has passed and the 10 s sweep has run —
  // polled through the API, up to 50 s, so a green UI state alone
  // cannot pass this step.
  await expect
    .poll(async () => (await sellerPayoutForOrder(request, sellerToken, orderId)).status, {
      timeout: 50_000,
    })
    .toBe('PAYABLE');
  const payableTotals = await sellerPayoutTotals(request, sellerToken);
  expect(payableTotals.payableCount, 'totals carry 1 payable payout').toBe(1);
  expect(payableTotals.payableCents, 'payable total is the order amount').toBe(1999);
  expect(payableTotals.pendingCount, 'nothing pending after promotion').toBe(0);
  const payoutId = (await sellerPayoutForOrder(request, sellerToken, orderId)).id;

  // The seeded ADMIN opens the payout ledger on the admin payments
  // page and marks this row paid by hand. The button sits behind a
  // native window.confirm, so the dialog is accepted explicitly —
  // Playwright auto-dismisses unhandled dialogs, which would cancel
  // the mark-paid.
  await logoutThroughUi(page);
  await loginThroughUi(page, ADMIN_ACCOUNT);
  await page.getByRole('link', { name: 'Admin payments' }).click();
  await expect(page).toHaveURL('/admin/payments');
  await expect(page.getByRole('heading', { name: 'Admin payments' })).toBeVisible();
  page.on('dialog', (dialog) => dialog.accept());
  const markPaidButton = page.getByRole('button', {
    name: `Mark payout ${payoutId} paid`,
  });
  await expect(markPaidButton).toBeVisible();
  await markPaidButton.click();
  await expect(page.getByText(`Marked payout #${payoutId} as paid.`)).toBeVisible();

  // The API pin: the totals now read 1 paid (¥19.99), nothing pending
  // or payable — the ledger advanced, not just the admin page.
  const paidTotals = await sellerPayoutTotals(request, sellerToken);
  expect(paidTotals.paidCount, 'totals carry 1 paid payout').toBe(1);
  expect(paidTotals.paidCents, 'paid total is the order amount').toBe(1999);
  expect(paidTotals.pendingCount, 'nothing pending after mark-paid').toBe(0);
  expect(paidTotals.payableCount, 'nothing payable after mark-paid').toBe(0);
  expect(
    (await sellerPayoutForOrder(request, sellerToken, orderId)).status,
    'payout row is PAID after mark-paid',
  ).toBe('PAID');

  // The seller, in their own session, sees the same terminal state:
  // the totals line reads 1 paid (¥19.99) and the row reads PAID.
  await logoutThroughUi(page);
  await loginThroughUi(page, seller);
  await page.getByRole('link', { name: 'Payouts' }).click();
  await expect(page).toHaveURL('/seller/payouts');
  await expect(page.getByText('1 paid (¥19.99)')).toBeVisible();
  await expect(page.getByText('0 pending (¥0.00)')).toBeVisible();
  const paidRow = page.locator('li', { hasText: `Payout for order #${orderId}` });
  await expect(paidRow).toContainText('PAID');
});
