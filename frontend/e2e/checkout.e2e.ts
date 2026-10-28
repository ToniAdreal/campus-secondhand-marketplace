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
 * The cart checkout journey (backlog #253) — the eighth flow of the
 * browser e2e smoke suite, and the first that starts from the cart
 * instead of Buy now: add to cart → cart checkout → pay → seller
 * completes with the handover code → buyer reviews. The seven earlier
 * flows live in smoke.e2e.ts; the shared account/API/login moulds live
 * in helpers.ts. Selectors are role/label based, like the rest of the
 * suite, never CSS paths.
 *
 * What this flow proves end to end, in the browser, that no earlier
 * flow covers:
 * - the cart path itself (#207): Add to cart from the detail page, the
 *   held row on the cart page, and checkout's per-item outcome list
 *   bridging to the created order's confirmation page;
 * - cart revalidation (#244) twice over: on cart open and again
 *   immediately before checkout — the held title/price survive both
 *   passes unchanged here (nothing was repriced or sold in between),
 *   so checkout commits on verified data and the outcome names the
 *   order instead of failing;
 * - the pay transition (#23) and its one-time handover-code disclosure
 *   (#179) on the confirmation page reached from the cart, not from
 *   Buy now;
 * - the handover-code completion: the seller completes with the buyer's
 *   code on the Sales page and the buyer sees COMPLETED — the code
 *   confirming receipt, which is what unlocks the review;
 * - the review step (#175): only a COMPLETED row offers it, and the
 *   submitted review is pinned afterwards through the API on the
 *   seller's profile (rating + body + order id), so a green UI
 *   confirmation alone cannot pass this flow.
 *
 * What it does NOT cover, stated plainly: the ship → buyer
 * confirm-receipt alternative (#222) reaches COMPLETED by a different
 * path and stays covered by its own unit tests — this journey proves
 * the handover-code path to COMPLETED, not both. And the listing is
 * seeded through the real API (register + create listing), never a
 * fixture; only the completion/review themselves are browser-driven,
 * the flow-5 mould.
 *
 * Credential-bucket arithmetic (this file's own runs; the bucket is
 * shared per IP with the smoke file, but the files run serially and
 * every login resets it): 2 registers + 3 logins, worst run between
 * resets is 3 of 5 —
 *   register seller + register buyer + buyer login = 3
 *   seller login (after the buyer login reset)     = 1
 *   buyer login (after the seller login reset)     = 1
 * Order create/pay/complete/review calls carry a Bearer token and
 * never touch the credential bucket.
 *
 * Stack note: like the other seven flows, this spec runs in the
 * existing CI `e2e` job (vite preview + backend jar on H2, picked up
 * automatically by the config's `**\/*.e2e.ts` match) — no new job,
 * no second stack. The docker-compose job stays curl-only.
 */

test('cart checkout, pay, seller completes with the handover code, buyer reviews', async ({
  page,
  request,
}) => {
  const seller = newAccount('e2esell');
  const buyer = newAccount('e2ebuy');
  const sellerToken = await registerViaApi(request, seller);
  const buyerToken = await registerViaApi(request, buyer);
  const title = `E2E cart journey textbook ${uniqueSuffix()}`;
  const itemId = await createListingViaApi(request, sellerToken, title);

  await loginThroughUi(page, buyer);

  // Browse → detail, like a real buyer (the card link, not a typed URL).
  const cardLink = page.getByRole('link', { name: title });
  await expect(cardLink).toBeVisible();
  await cardLink.click();
  await expect(page).toHaveURL(new RegExp(`/items/${itemId}$`));
  await expect(page.getByRole('heading', { name: title })).toBeVisible();

  // The cart path, not Buy now: add, then follow the notice's own link
  // into the cart (the nav "Cart" link is NOT used here: its accessible
  // name "Cart" is a substring of the notice's "View cart" link, so a
  // role+name lookup for it would match both links under Playwright's
  // substring name matching and trip strict mode).
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await expect(page.getByText('Added to your cart.')).toBeVisible();
  await page.getByRole('link', { name: 'View cart' }).click();
  await expect(page).toHaveURL('/cart');
  await expect(page.getByRole('heading', { name: 'Your cart' })).toBeVisible();
  // Revalidated on open (#244): same title, same ¥19.99, nothing dropped.
  await expect(page.getByRole('link', { name: title })).toBeVisible();
  await expect(page.getByText('Total: ¥19.99')).toBeVisible();

  // Checkout revalidates again immediately before ordering (#244) and
  // reports one outcome per item; the outcome's order link is the
  // journey's bridge to the confirmation page.
  await page.getByRole('button', { name: 'Check out' }).click();
  await expect(page.getByRole('heading', { name: 'Checkout results' })).toBeVisible();
  const orderLink = page.getByRole('link', { name: /order #\d+/ });
  await expect(orderLink).toBeVisible();
  const orderHref = await orderLink.getAttribute('href');
  const orderMatch = /\/orders\/(\d+)$/.exec(orderHref ?? '');
  expect(orderMatch, 'checkout outcome links to the created order').toBeTruthy();
  const orderId = Number(orderMatch?.[1]);
  await orderLink.click();
  await expect(page).toHaveURL(new RegExp(`/orders/${orderId}$`));
  await expect(page.getByRole('heading', { name: 'Order confirmed' })).toBeVisible();

  // Paying discloses the handover code exactly once, on this page (#179).
  await page.getByRole('button', { name: 'Pay now (demo)' }).click();
  const codeValue = page
    .locator('p', { hasText: 'Your handover code:' })
    .locator('strong');
  await expect(codeValue).toBeVisible();
  const handoverCode = (await codeValue.textContent())?.trim() ?? '';
  expect(handoverCode, 'pay discloses a 6-digit handover code').toMatch(/^\d{6}$/);
  await expect(page.getByText('Paid (demo capture)')).toBeVisible();

  // The seller completes with the buyer's code; the row flips to COMPLETED.
  await logoutThroughUi(page);
  await loginThroughUi(page, seller);
  await page.getByRole('link', { name: 'Sales' }).click();
  await expect(page).toHaveURL('/seller/orders');
  await expect(page.getByRole('heading', { name: 'Orders on my listings' })).toBeVisible();
  await expectOrderRowStatus(page, orderId, 'PAID');
  const salesRow = page.locator('li', { hasText: `Order #${orderId}` });
  await salesRow.getByLabel(/Handover code/).fill(handoverCode);
  await salesRow.getByRole('button', { name: 'Complete order' }).click();
  await expectOrderRowStatus(page, orderId, 'COMPLETED');

  // The buyer reviews the COMPLETED order from My orders (#175).
  await logoutThroughUi(page);
  await loginThroughUi(page, buyer);
  await page.getByRole('link', { name: 'My orders' }).click();
  await expect(page).toHaveURL('/orders');
  await expect(page.getByRole('heading', { name: 'My orders' })).toBeVisible();
  await expectOrderRowStatus(page, orderId, 'COMPLETED');
  const buyerRow = page.locator('li', { hasText: `Order #${orderId}` });
  await buyerRow.getByRole('button', { name: 'Leave a review' }).click();
  await buyerRow.getByLabel('Your rating').selectOption('5');
  await buyerRow
    .getByLabel('Anything to add? (optional)')
    .fill('Textbook arrived in great condition, fast handover.');
  await buyerRow.getByRole('button', { name: 'Submit review' }).click();
  await expect(buyerRow).toContainText('Review submitted — thanks for helping other buyers.');

  // The end-to-end pin: the review persisted on the seller's profile,
  // at this order, with this rating and body — not just a UI toast.
  const itemRes = await request.get(`/api/items/${itemId}`, {
    headers: { Authorization: `Bearer ${buyerToken}` },
  });
  expect(itemRes.status(), 'API item read should succeed').toBe(200);
  const itemBody = await itemRes.json();
  const sellerId = itemBody.data.sellerId as number;
  const reviewsRes = await request.get(`/api/users/${sellerId}/reviews`, {
    headers: { Authorization: `Bearer ${buyerToken}` },
  });
  expect(reviewsRes.status(), 'API seller reviews should succeed').toBe(200);
  const reviewsBody = await reviewsRes.json();
  const review = (
    reviewsBody.data.content as Array<{
      orderId: number;
      rating: number;
      body: string | null;
    }>
  ).find((r) => r.orderId === orderId);
  expect(review, 'the completed order carries exactly this review').toBeTruthy();
  expect(review?.rating).toBe(5);
  expect(review?.body).toBe('Textbook arrived in great condition, fast handover.');
});
