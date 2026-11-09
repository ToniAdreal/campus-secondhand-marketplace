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
 * The favorites + seller-follow flow (backlog #352) — the eleventh
 * flow of the browser e2e smoke suite: a signed-in buyer favorites a
 * listing from its detail page and finds it on /favorites (and
 * unfavorites it back off from there), then follows the listing's
 * seller from the seller profile and sees the follow state persist
 * across a reload; plus the negative case for guests. The ten earlier
 * flows live in smoke.e2e.ts (seven), checkout.e2e.ts (cart checkout),
 * messaging.e2e.ts (buyer–seller messaging) and moderation.e2e.ts
 * (listing report → admin action); the shared account/API/login
 * moulds live in helpers.ts. Selectors are role/label based, like the
 * rest of the suite, never CSS paths.
 *
 * What this flow proves end to end, in the browser, that no earlier
 * flow covers:
 * - the favorite toggle (#143) as a round trip: the buyer saves a
 *   listing from its detail page (the heart flips to the saved state),
 *   the listing appears on /favorites with its real status badge, and
 *   unsaving it from the /favorites card removes it there — the
 *   favorites list query is the single source of truth on both
 *   surfaces, so a toggle on one surface is visible on the other;
 * - the seller follow (#217) as a round trip: the buyer follows the
 *   listing's seller from the seller profile (the button flips to the
 *   Following state) and the state survives a full page reload — the
 *   follow is a server row, not local state;
 * - the guest boundary: a signed-out visitor sees no favorite control
 *   on the detail page at all, and a direct visit to /favorites
 *   bounces to /login.
 *
 * Negative-case note, stated plainly because the backlog item words
 * it differently: the item says "a guest clicking favorite is routed
 * to login", but that click cannot happen — FavoriteButton renders
 * nothing for anonymous visitors by #143's deliberate design (the
 * backend would 401 the writes anyway), so there is no favorite
 * control for a guest to click. This flow pins the real guest
 * behaviour instead (no button on the detail page; /favorites itself
 * bounces to /login) and changes no component. If the product ever
 * wants a guest-facing heart that routes to login, that is a
 * behaviour change for its own backlog item, not this flow.
 *
 * What it does NOT cover, stated plainly: the saved-searches section
 * that shares the /favorites page is covered by its own e2e flow
 * (backlog #359, savedSearches.e2e.ts) — this flow never touches it;
 * the follow's notification side (#217) and the per-user
 * favorite/follow rate limits stay covered by their own backend tests. The listing is seeded through
 * the real API (register + create listing), never a fixture; only the
 * favorite, the follow and the guest checks are browser-driven, the
 * flow-5 mould.
 *
 * Credential-bucket arithmetic (this file's own runs; the bucket is
 * shared per IP with the smoke, checkout, messaging and moderation
 * files, but the files run serially and every login resets it):
 * 2 registers + 1 login, worst run between resets is 3 of 5 —
 *   register seller + register buyer + buyer login = 3
 * Logout is not a credential endpoint, and the guest checks that
 * follow it need no login at all. The favorite/follow writes carry a
 * Bearer <redacted> and never touch the credential bucket.
 *
 * Stack note: like the other ten flows, this spec runs in the
 * existing CI `e2e` job (vite preview + backend jar on H2, picked up
 * automatically by the config's `**\/*.e2e.ts` match) — no new job,
 * no second stack. The docker-compose job stays curl-only.
 */

test('buyer favorites a listing, finds it on /favorites, unfavorites it, follows the seller, and a guest gets no favorite control', async ({
  page,
  request,
}) => {
  const seller = newAccount('e2esell');
  const buyer = newAccount('e2ebuy');
  const sellerToken = await registerViaApi(request, seller);
  await registerViaApi(request, buyer);
  const title = `E2E favorite textbook ${uniqueSuffix()}`;
  const itemId = await createListingViaApi(request, sellerToken, title);

  await loginThroughUi(page, buyer);

  // The buyer saves the listing from its detail page; the heart
  // flips to the saved state.
  const card = page.getByRole('link', { name: title });
  await expect(card).toBeVisible();
  await card.click();
  await expect(page).toHaveURL(new RegExp(`/items/${itemId}$`));
  await page.getByRole('button', { name: 'Save to favorites' }).click();
  await expect(page.getByRole('button', { name: 'Remove from favorites' })).toBeVisible();

  // The saved listing shows on /favorites with its real status badge.
  await page.getByRole('link', { name: 'Favorites' }).click();
  await expect(page).toHaveURL('/favorites');
  await expect(page.getByRole('heading', { name: 'Favorites' })).toBeVisible();
  const favoriteCard = page.locator('li', { hasText: title });
  await expect(favoriteCard).toBeVisible();
  await expect(favoriteCard).toContainText('AVAILABLE');

  // Unsaving from the /favorites card removes it there — the card
  // unmounts and the empty state takes its place.
  await favoriteCard.getByRole('button', { name: 'Remove from favorites' }).click();
  await expect(page.getByText("You haven't saved any listings yet.")).toBeVisible();
  await expect(page.getByRole('link', { name: title })).toHaveCount(0);

  // The buyer follows the listing's seller from the seller profile,
  // reached through the detail page's "Sold by" line, and the follow
  // state survives a full reload.
  await page.goto(`/items/${itemId}`);
  await expect(page).toHaveURL(new RegExp(`/items/${itemId}$`));
  await page.getByRole('link', { name: seller.username }).click();
  await expect(page).toHaveURL(new RegExp(`/seller/\\d+$`));
  await expect(page.getByRole('heading', { name: seller.username })).toBeVisible();
  await page.getByRole('button', { name: `Follow ${seller.username}` }).click();
  await expect(
    page.getByRole('button', { name: `Unfollow ${seller.username}` }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole('button', { name: `Unfollow ${seller.username}` }),
  ).toBeVisible();

  // The guest boundary: signed out, the detail page offers no
  // favorite control at all, and /favorites bounces to /login.
  await logoutThroughUi(page);
  await page.goto(`/items/${itemId}`);
  await expect(page).toHaveURL(new RegExp(`/items/${itemId}$`));
  await expect(page.getByRole('button', { name: 'Save to favorites' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Remove from favorites' })).toHaveCount(0);
  await page.goto('/favorites');
  await expect(page).toHaveURL('/login');
  await expect(page.getByRole('heading', { name: 'Login' })).toBeVisible();
});
