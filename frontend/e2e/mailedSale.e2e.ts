import { expect, test } from '@playwright/test';
import {
  createListingViaApi,
  expectOrderRowStatus,
  loginThroughUi,
  logoutThroughUi,
  newAccount,
  registerViaApi,
  uniqueSuffix,
} from './helpers';

/**
 * The mailed-sale journey (backlog #370) — the thirteenth flow of the
 * browser e2e smoke suite, and the first that proves the ship →
 * confirm-receipt path to COMPLETED. The seven earlier flows live in
 * smoke.e2e.ts, the eighth (cart checkout) in checkout.e2e.ts, the
 * ninth (messaging) in messaging.e2e.ts, the tenth (moderation) in
 * moderation.e2e.ts, the eleventh (favorites + seller-follow) in
 * favorites.e2e.ts, and the twelfth (saved-searches) in
 * savedSearches.e2e.ts; the shared account/API/login moulds live
 * in helpers.ts. Selectors are role/label based, like the rest of the
 * suite, never CSS paths.
 *
 * What this flow proves end to end, in the browser, that no earlier
 * flow covers:
 * - the mailed-sale path itself (#222): buy-now → pay → the seller
 *   ships with a carrier + tracking number on the Sales page → the
 *   buyer confirms receipt on My orders → COMPLETED, the alternative
 *   to the handover-code completion flows 4/5/8 prove. The
 *   checkout journey's header used to state this path "stays covered
 *   by its own unit tests" — it no longer does;
 * - the ship form: the PAID row offers carrier + tracking number
 *   next to the handover completion, and shipping flips the row to
 *   SHIPPED with the recorded carrier + tracking number shown on
 *   both the seller row and the buyer row;
 * - the listing stays RESERVED while the order is SHIPPED, pinned
 *   through the API between the ship and the receipt confirmation —
 *   the sale is not final until the buyer says the parcel arrived;
 * - the receipt confirmation: only a SHIPPED row offers it, and the
 *   confirmed order reads COMPLETED in the buyer's own history while
 *   the listing flips to SOLD, pinned afterwards through the API, so
 *   a green UI confirmation alone cannot pass this flow.
 *
 * What it does NOT cover, stated plainly: the handover-code path to
 * COMPLETED (flows 4/5/8); a dispute on a SHIPPED order (#169 — the
 * window and the freeze stay unit-covered); and a refund after ship
 * (no such transition exists — a shipped order can only complete or
 * be disputed). And the listing is seeded through the real API
 * (register + create listing), never a fixture; only the
 * buy/pay/ship/confirm transitions themselves are browser-driven,
 * the flow-5 mould.
 *
 * Credential-bucket arithmetic (this file's own runs; the bucket is
 * shared per IP with the smoke file, but the files run serially and
 * every login resets it): 2 registers + 3 logins, worst run between
 * resets is 3 of 5 —
 *   register seller + register buyer + buyer login = 3
 *   seller login (after the buyer login reset)     = 1
 *   buyer login (after the seller login reset)     = 1
 * Order create/pay/ship/confirm calls carry a Bearer token and
 * never touch the credential bucket.
 *
 * Stack note: like the other twelve flows, this spec runs in the
 * existing CI `e2e` job (vite preview + backend jar on H2, picked up
 * automatically by the config's `**\/*.e2e.ts` match) — no new job,
 * no second stack. The docker-compose job stays curl-only.
 */

test('buy now, pay, seller ships, buyer confirms receipt, listing sold', async ({
  page,
  request,
}) => {
  const seller = newAccount('e2esell');
  const buyer = newAccount('e2ebuy');
  const sellerToken = await registerViaApi(request, seller);
  const buyerToken = await registerViaApi(request, buyer);
  const title = `E2E mailed sale textbook ${uniqueSuffix()}`;
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
  // The mailed path never uses it — the code is asserted here only so
  // the pay step itself is proven, exactly as flow 4 does.
  await page.getByRole('button', { name: 'Pay now (demo)' }).click();
  const codeValue = page
    .locator('p', { hasText: 'Your handover code:' })
    .locator('strong');
  await expect(codeValue).toBeVisible();
  expect((await codeValue.textContent())?.trim()).toMatch(/^\d{6}$/);
  await expect(page.getByText('Paid (demo capture)')).toBeVisible();

  // The seller ships with a carrier + tracking number; the row flips to
  // SHIPPED and shows what was recorded.
  await logoutThroughUi(page);
  await loginThroughUi(page, seller);
  await page.getByRole('link', { name: 'Sales' }).click();
  await expect(page).toHaveURL('/seller/orders');
  await expect(page.getByRole('heading', { name: 'Orders on my listings' })).toBeVisible();
  await expectOrderRowStatus(page, orderId, 'PAID');
  const salesRow = page.locator('li', { hasText: `Order #${orderId}` });
  await salesRow.getByLabel('Carrier').fill('SF Express');
  await salesRow.getByLabel('Tracking number').fill('SF1234567890');
  await salesRow.getByRole('button', { name: 'Ship order' }).click();
  await expectOrderRowStatus(page, orderId, 'SHIPPED');
  await expect(salesRow).toContainText(
    'Shipped via SF Express — tracking SF1234567890. The listing stays reserved until the buyer confirms receipt.',
  );

  // The listing stays RESERVED while the order is SHIPPED — pinned
  // through the API, so the sale is not final until receipt is confirmed.
  const shippedItemRes = await request.get(`/api/items/${itemId}`, {
    headers: { Authorization: `Bearer ${buyerToken}` },
  });
  expect(shippedItemRes.status(), 'API item read should succeed').toBe(200);
  const shippedItemBody = await shippedItemRes.json();
  expect(shippedItemBody.data.status, 'listing stays RESERVED while SHIPPED').toBe('RESERVED');

  // The buyer confirms receipt from My orders; the row flips to COMPLETED.
  await logoutThroughUi(page);
  await loginThroughUi(page, buyer);
  await page.getByRole('link', { name: 'My orders' }).click();
  await expect(page).toHaveURL('/orders');
  await expect(page.getByRole('heading', { name: 'My orders' })).toBeVisible();
  await expectOrderRowStatus(page, orderId, 'SHIPPED');
  const buyerRow = page.locator('li', { hasText: `Order #${orderId}` });
  await expect(buyerRow).toContainText('Shipped via SF Express — tracking SF1234567890.');
  await buyerRow.getByRole('button', { name: 'Confirm receipt' }).click();
  await expect(buyerRow).toContainText('Receipt confirmed — thanks! The order is now complete.');
  await expectOrderRowStatus(page, orderId, 'COMPLETED');

  // The end-to-end pin: the listing flipped to SOLD on the receipt
  // confirmation — not just a UI status change on the order row.
  const soldItemRes = await request.get(`/api/items/${itemId}`, {
    headers: { Authorization: `Bearer ${buyerToken}` },
  });
  expect(soldItemRes.status(), 'API item read should succeed').toBe(200);
  const soldItemBody = await soldItemRes.json();
  expect(soldItemBody.data.status, 'listing is SOLD after receipt confirmation').toBe('SOLD');
});
