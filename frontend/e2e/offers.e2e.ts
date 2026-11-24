import { expect, test, type APIRequestContext } from '@playwright/test';
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
 * The price-offer journey (backlog #433) — the eighteenth flow of the
 * browser e2e smoke suite: a buyer makes a price offer on an AVAILABLE
 * listing, the seller accepts it directly in the Offers inbox (no
 * counter), and the order exists at the offered price. The seventeen
 * earlier flows live in smoke.e2e.ts (seven), checkout.e2e.ts (cart
 * checkout), messaging.e2e.ts (buyer–seller messaging),
 * moderation.e2e.ts (listing report → admin action), favorites.e2e.ts
 * (favorites + seller-follow), savedSearches.e2e.ts (saved-searches),
 * mailedSale.e2e.ts (mailed sale), appeals.e2e.ts (seller appeal),
 * payouts.e2e.ts (seller payout), passwordReset.e2e.ts (password
 * reset) and disputeRelease.e2e.ts (dispute resolve-release); the
 * shared account/API/login moulds live in helpers.ts. Selectors are
 * role/label based, like the rest of the suite, never CSS paths.
 *
 * What this flow proves end to end, in the browser, that no earlier
 * flow covers:
 * - the direct seller accept as a journey (#168): flow 6 proves the
 *   counter path only — offer → seller counters → buyer
 *   counter-accepts → order at the counter price — and the seller's
 *   "Accept" button on a PENDING received offer has never been driven
 *   in the browser, so a dead accept control or a wrong-price order
 *   on the plain accept path would ship green on unit tests alone;
 * - the order at the OFFERED price: the listing asks ¥19.99, the
 *   buyer offers ¥15.00, and the accept creates the order at 1500
 *   cents — not the asking price, not a counter price (there is no
 *   counter here), pinned through the API at each step;
 * - the buyer-side result, pinned through the API at each step: the
 *   made-offers read shows the offer PENDING at 1500 cents before
 *   the accept and ACCEPTED with the order id after it, the order
 *   reads PENDING with amountCents 1500, and the buyer, in their own
 *   session, sees ¥15.00 on the order confirmation page and the
 *   PENDING chip on My orders — so a green UI confirmation alone
 *   cannot pass this flow.
 *
 * What it does NOT cover, stated plainly: the counter path (flow 6),
 * decline / withdraw / expiry of an offer (unit-covered in
 * PriceOfferTest, not driven here), a second live offer 409
 * (unit-covered), and paying the accepted order — the mock capture
 * on an offer-created order is the same path flows 4/5/6 already
 * drive, and this flow's order stays PENDING.
 *
 * Credential-bucket arithmetic (this file's own runs; the bucket is
 * shared per IP with the smoke file, but the files run serially and
 * every login resets it): 2 registers + 3 logins, worst run between
 * resets is 3 of 5 —
 *   register seller + register buyer + buyer login = 3
 *   seller login (after the buyer login reset)     = 1
 *   buyer login (after the seller login reset)     = 1
 * Offer create/accept calls carry a Bearer token and never touch the
 * credential bucket; the offers reads are plain authenticated GETs.
 *
 * Stack note: like the other seventeen flows, this spec runs in the
 * existing CI `e2e` job (vite preview + backend jar on H2, picked up
 * automatically by the config's `**\/*.e2e.ts` match) — no new job,
 * no second stack. The docker-compose job stays curl-only.
 */

interface MadeOffer {
  id: number;
  itemId: number;
  priceCents: number;
  status: string;
  orderId: number | null;
}

/**
 * Reads the caller's made offers through the public API (backlog #168:
 * GET /api/offers/made, newest first). Local to this spec — the
 * helpers module holds only the moulds shared across specs, and the
 * offer readers stay with the spec that uses them.
 */
async function getMadeOffersViaApi(
  request: APIRequestContext,
  buyerToken: string,
): Promise<MadeOffer[]> {
  const res = await request.get('/api/offers/made', {
    headers: { Authorization: `Bearer ${buyerToken}` },
  });
  expect(res.status(), 'API read made offers should succeed').toBe(200);
  const body = await res.json();
  return body.data.content as MadeOffer[];
}

/**
 * Reads one order back through the public API (backlog #213 flow 6
 * pins the created order's amount this way). Used here to pin the
 * order's status and amount at the agreed price — PENDING with
 * amountCents 1500, not the 1999 asking price.
 */
async function getOrderViaApi(
  request: APIRequestContext,
  buyerToken: string,
  orderId: number,
): Promise<{ status: string; amountCents: number }> {
  const res = await request.get(`/api/orders/${orderId}`, {
    headers: { Authorization: `Bearer ${buyerToken}` },
  });
  expect(res.status(), 'API read order should succeed').toBe(200);
  const body = await res.json();
  return body.data as { status: string; amountCents: number };
}

test('buyer offers, seller accepts, and the order exists at the offered price', async ({
  page,
  request,
}) => {
  const seller = newAccount('e2esell');
  const buyer = newAccount('e2ebuy');
  const sellerToken = await registerViaApi(request, seller);
  const buyerToken = await registerViaApi(request, buyer);
  const title = `E2E price-offer textbook ${uniqueSuffix()}`;
  // Asking price ¥19.99 (createListingViaApi's fixed 1999 cents). The
  // offer is ¥15.00 — two distinct amounts, so the final amount pin
  // cannot pass on the asking price.
  const itemId = await createListingViaApi(request, sellerToken, title);

  await loginThroughUi(page, buyer);

  // Browse → detail, then make the ¥15.00 offer from the detail page.
  const cardLink = page.getByRole('link', { name: title });
  await expect(cardLink).toBeVisible();
  await cardLink.click();
  await expect(page).toHaveURL(new RegExp(`/items/${itemId}$`));
  await page.getByLabel('Make an offer (¥)').fill('15');
  await page.getByRole('button', { name: 'Make an offer' }).click();
  await expect(
    page.getByText('Offer sent — the seller can accept or decline it. Track it under Offers.'),
  ).toBeVisible();

  // The API pin: the made-offers read shows the offer PENDING at
  // 1500 cents, with no order behind it yet.
  const madeBefore = await getMadeOffersViaApi(request, buyerToken);
  const offerBefore = madeBefore.find((offer) => offer.itemId === itemId);
  expect(offerBefore, 'the made offer exists for this listing').toBeTruthy();
  expect(offerBefore?.priceCents).toBe(1500);
  expect(offerBefore?.status).toBe('PENDING');
  expect(offerBefore?.orderId).toBeNull();

  // The seller accepts the offer from the Offers inbox: the row flips
  // to ACCEPTED and announces the created order.
  await logoutThroughUi(page);
  await loginThroughUi(page, seller);
  await page.getByRole('link', { name: 'Offers' }).click();
  await expect(page).toHaveURL('/offers');
  const receivedRow = page.locator('li', { hasText: `Listing #${itemId}` });
  await expect(receivedRow).toContainText('offered ¥15.00');
  await expect(receivedRow).toContainText('PENDING');
  await receivedRow.getByRole('button', { name: 'Accept' }).click();
  await expect(receivedRow).toContainText('ACCEPTED');
  await expect(receivedRow).toContainText('was created at the agreed price.');
  const receivedText = (await receivedRow.textContent()) ?? '';
  const orderMatch = /Order #(\d+) was created/.exec(receivedText);
  expect(orderMatch, 'accept announces the created order id').toBeTruthy();
  const orderId = Number(orderMatch?.[1]);

  // The API pin: the offer is ACCEPTED with the order id, and the
  // order exists at the OFFERED price — 1500 cents, not the 1999
  // asking price — still PENDING, never paid by this flow.
  const madeAfter = await getMadeOffersViaApi(request, buyerToken);
  const offerAfter = madeAfter.find((offer) => offer.itemId === itemId);
  expect(offerAfter?.status).toBe('ACCEPTED');
  expect(offerAfter?.orderId).toBe(orderId);
  const order = await getOrderViaApi(request, buyerToken, orderId);
  expect(order.status).toBe('PENDING');
  expect(order.amountCents).toBe(1500);

  // The buyer, in their own session, sees the order at the agreed
  // price: the confirmation page shows ¥15.00 and My orders reads
  // PENDING.
  await logoutThroughUi(page);
  await loginThroughUi(page, buyer);
  await page.goto(`/orders/${orderId}`);
  await expect(page.getByRole('heading', { name: 'Order confirmed' })).toBeVisible();
  await expect(page.getByText('¥15.00')).toBeVisible();
  await page.getByRole('link', { name: 'My orders' }).click();
  await expect(page).toHaveURL('/orders');
  await expectOrderRowStatus(page, orderId, 'PENDING');
});
