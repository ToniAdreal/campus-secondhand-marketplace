import { expect, test } from '@playwright/test';
import {
  createListingViaApi,
  loginThroughUi,
  logoutThroughUi,
  newAccount,
  registerViaApi,
  uniqueSuffix,
} from './helpers';

/**
 * The buyer–seller messaging flow (backlog #342) — the ninth flow of
 * the browser e2e smoke suite: buyer sends a message on an item detail
 * page, the seller sees the thread and replies, the buyer sees the
 * reply — plus the #112 pairing rule's negative case (a third user
 * cannot open the thread). The eight earlier flows live in
 * smoke.e2e.ts (seven) and checkout.e2e.ts (cart checkout); the shared
 * account/API/login moulds live in helpers.ts. Selectors are
 * role/label based, like the rest of the suite, never CSS paths.
 *
 * What this flow proves end to end, in the browser, that no earlier
 * flow covers:
 * - the message thread as a surface (#13/#31): the buyer writes from
 *   the detail page, the seller reads the same thread from the same
 *   page in their own session and replies, and the buyer reads the
 *   reply back — the whole round trip through the real POST/GET
 *   /api/messages endpoints, with the send's query invalidation
 *   (#16 doctrine) doing the refresh, never a manual reload;
 * - the #112 pairing rule from the UI side: a third user who never
 *   participated opens the same detail page and gets the privacy
 *   note, not the thread — the backend answers the thread read with
 *   403 and MessageThread hides the conversation behind it, so the
 *   buyer's message body is absent from that session's DOM.
 *
 * What it does NOT cover, stated plainly: message edit/delete (#206),
 * photo attachments (#245), "load earlier messages" pagination (#41)
 * and message search (#260) stay covered by their own unit tests —
 * this flow proves the first-contact send, the seller reply, and the
 * non-participant read guard. The listing is seeded through the real
 * API (register + create listing), never a fixture; only the
 * messaging itself is browser-driven, the flow-5 mould.
 *
 * Credential-bucket arithmetic (this file's own runs; the bucket is
 * shared per IP with the smoke and checkout files, but the files run
 * serially and every login resets it): 3 registers + 4 logins, worst
 * run between resets is 3 of 5 —
 *   register seller + register buyer + buyer login = 3
 *   seller login (after the buyer login reset)     = 1
 *   buyer login (after the seller login reset)     = 1
 *   register third + third login                   = 2
 * The third user is registered last, just before their own login, so
 * no run stacks past 3. Message sends carry a Bearer token and never
 * touch the credential bucket.
 *
 * Stack note: like the other ten flows, this spec runs in the
 * existing CI `e2e` job (vite preview + backend jar on H2, picked up
 * automatically by the config's `**\/*.e2e.ts` match) — no new job,
 * no second stack. The docker-compose job stays curl-only.
 */

test('buyer messages, seller replies, buyer sees the reply — and a third user cannot open the thread', async ({
  page,
  request,
}) => {
  const seller = newAccount('e2esell');
  const buyer = newAccount('e2ebuy');
  const sellerToken = await registerViaApi(request, seller);
  await registerViaApi(request, buyer);
  const title = `E2E messaging textbook ${uniqueSuffix()}`;
  const itemId = await createListingViaApi(request, sellerToken, title);
  const buyerBody = `Hi, is this textbook still available? ${uniqueSuffix()}`;
  const sellerBody = `Yes, it is — happy to meet on campus. ${uniqueSuffix()}`;

  await loginThroughUi(page, buyer);

  // Browse → detail, like a real buyer (the card link, not a typed URL).
  const cardLink = page.getByRole('link', { name: title });
  await expect(cardLink).toBeVisible();
  await cardLink.click();
  await expect(page).toHaveURL(new RegExp(`/items/${itemId}$`));

  // The buyer writes to the seller from the thread on the detail page;
  // the send's invalidation refetches the thread, so the message
  // appears without a reload.
  await page.getByLabel('Message the seller').fill(buyerBody);
  await page.getByRole('button', { name: 'Send', exact: true }).click();
  await expect(page.getByText(buyerBody)).toBeVisible();

  // The seller reads the same thread from the same page in their own
  // session and replies to the buyer (the label carries the buyer's id,
  // which this spec never needs to know).
  await logoutThroughUi(page);
  await loginThroughUi(page, seller);
  const sellerCardLink = page.getByRole('link', { name: title });
  await expect(sellerCardLink).toBeVisible();
  await sellerCardLink.click();
  await expect(page).toHaveURL(new RegExp(`/items/${itemId}$`));
  await expect(page.getByText(buyerBody)).toBeVisible();
  await page.getByLabel(/Reply to User #\d+/).fill(sellerBody);
  await page.getByRole('button', { name: 'Send', exact: true }).click();
  await expect(page.getByText(sellerBody)).toBeVisible();

  // The buyer, in their own session, sees the seller's reply.
  await logoutThroughUi(page);
  await loginThroughUi(page, buyer);
  const buyerCardLink = page.getByRole('link', { name: title });
  await expect(buyerCardLink).toBeVisible();
  await buyerCardLink.click();
  await expect(page).toHaveURL(new RegExp(`/items/${itemId}$`));
  await expect(page.getByText(buyerBody)).toBeVisible();
  await expect(page.getByText(sellerBody)).toBeVisible();

  // The #112 negative case: a third user who never participated opens
  // the same detail page and gets the privacy note, not the thread —
  // the buyer's message body is absent from this session's DOM.
  const third = newAccount('e2ethird');
  await registerViaApi(request, third);
  await logoutThroughUi(page);
  await loginThroughUi(page, third);
  const thirdCardLink = page.getByRole('link', { name: title });
  await expect(thirdCardLink).toBeVisible();
  await thirdCardLink.click();
  await expect(page).toHaveURL(new RegExp(`/items/${itemId}$`));
  await expect(
    page.getByText('This conversation is private — only participants can see it.'),
  ).toBeVisible();
  await expect(page.getByText(buyerBody)).toHaveCount(0);
  await expect(page.getByText(sellerBody)).toHaveCount(0);
});
