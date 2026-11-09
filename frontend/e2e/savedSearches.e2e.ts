import { expect, test } from '@playwright/test';
import {
  createListingViaApi,
  loginThroughUi,
  newAccount,
  registerViaApi,
  uniqueSuffix,
} from './helpers';

/**
 * The saved-searches flow (backlog #359) — the twelfth flow of the
 * browser e2e smoke suite: a signed-in buyer filters the browse page
 * by a keyword, saves that search from the browse page, finds it in
 * the saved-searches section on /favorites with its live match count,
 * runs it and lands back on the exact browse URL with the listing
 * still there, then deletes it and sees it gone; plus the negative
 * case, where a browse page with no filter active offers no save
 * control at all. The eleven earlier flows live in smoke.e2e.ts
 * (seven), checkout.e2e.ts (cart checkout), messaging.e2e.ts
 * (buyer–seller messaging), moderation.e2e.ts (listing report →
 * admin action) and favorites.e2e.ts (favorites + seller follow);
 * the shared account/API/login moulds live in helpers.ts. Selectors
 * are role/label based, like the rest of the suite, never CSS paths.
 *
 * What this flow proves end to end, in the browser, that no earlier
 * flow covers:
 * - the save round trip (#164) as a browser journey: the "Save this
 *   search" button appears only once a filter is active, the save
 *   confirms in place, and the row shows on /favorites with the
 *   keyword description and the backend-computed match count — the
 *   saved-search list query is the single source of truth, so a save
 *   on the browse page is visible on the favorites page;
 * - the run round trip: the saved row's link restores the exact
 *   browse URL the filters describe (?q=, no stray ?page= or sort),
 *   and the seeded listing is still in the filtered result — the
 *   restore path is the same URL the browse page itself writes;
 * - the delete round trip: deleting the row from /favorites removes
 *   it there and the section falls back to its empty state — the
 *   DELETE /api/users/me/saved-searches/{id} mutation invalidates
 *   the same list query the save used.
 *
 * Negative-case note: the item words it as "saving with no filter
 * active offers no save control" — this flow pins exactly that
 * shipped behaviour (HomePage renders SaveSearchButton only when a
 * filter differs from the default browse, or a non-default sort is
 * set), with a fresh, unfiltered browse page, and changes no
 * component.
 *
 * What it does NOT cover, stated plainly: the 20-per-user cap path
 * (422) stays unit-covered, as the item scopes it out; the
 * background-matching alerts (#182) and the notification fallback
 * that reads this same list stay covered by their own tests; the
 * category/price/sort filter shapes are exercised by their own
 * Vitest + RTL tests, not by this keyword-only journey. The listing
 * is seeded through the real API (register + create listing), never
 * a fixture; only the filtering, the save, the run and the delete
 * are browser-driven, the flow-5 mould.
 *
 * Credential-bucket arithmetic (this file's own runs; the bucket is
 * shared per IP with the smoke, checkout, messaging, moderation and
 * favorites files, but the files run serially and every login resets
 * it): 2 registers + 1 login, worst run between resets is 3 of 5 —
 *   register seller + register buyer + buyer login = 3
 * The save/delete writes carry a Bearer token and never touch the
 * credential bucket.
 *
 * Stack note: like the other eleven flows, this spec runs in the
 * existing CI `e2e` job (vite preview + backend jar on H2, picked up
 * automatically by the config's `**\/*.e2e.ts` match) — no new job,
 * no second stack. The docker-compose job stays curl-only.
 */

test('buyer saves a filtered browse search, runs it from /favorites, deletes it, and no filter means no save control', async ({
  page,
  request,
}) => {
  const seller = newAccount('e2esell');
  const buyer = newAccount('e2ebuy');
  const sellerToken = await registerViaApi(request, seller);
  await registerViaApi(request, buyer);
  // The keyword is the unique token itself, so the search matches
  // exactly this listing and nothing else in the shared H2.
  const keyword = `saved${uniqueSuffix()}`;
  const title = `E2E saved-search lamp ${keyword}`;
  await createListingViaApi(request, sellerToken, title);

  await loginThroughUi(page, buyer);

  // The negative case first: a fresh browse page with no filter
  // active offers no save control at all.
  await expect(page.getByRole('button', { name: 'Save this search' })).toHaveCount(0);

  // Filtering by the keyword commits ?q= to the URL (300 ms
  // debounce) and the save control appears; Escape dismisses the
  // search-suggestions dropdown so it cannot cover the button.
  await page.getByLabel('Search listings').fill(keyword);
  await page.keyboard.press('Escape');
  await expect(page).toHaveURL(`/?q=${keyword}`);
  await expect(page.getByRole('link', { name: title })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Save this search' })).toBeVisible();

  // Saving confirms in place.
  await page.getByRole('button', { name: 'Save this search' }).click();
  await expect(
    page.getByText('Saved — find it under Saved searches on your favorites page.'),
  ).toBeVisible();

  // The saved search shows on /favorites with its keyword
  // description and its live match count.
  await page.getByRole('link', { name: 'Favorites' }).click();
  await expect(page).toHaveURL('/favorites');
  await expect(page.getByRole('heading', { name: 'Favorites' })).toBeVisible();
  const savedRow = page.locator('li', { hasText: `"${keyword}"` });
  await expect(savedRow).toBeVisible();
  await expect(savedRow).toContainText('1 matching listing');

  // Running it lands back on the exact browse URL, with the listing
  // still in the filtered result.
  await savedRow.getByRole('link', { name: `"${keyword}"` }).click();
  await expect(page).toHaveURL(`/?q=${keyword}`);
  await expect(page.getByRole('heading', { name: 'Latest listings' })).toBeVisible();
  await expect(page.getByRole('link', { name: title })).toBeVisible();

  // Deleting it from /favorites removes the row and the section
  // falls back to its empty state.
  await page.getByRole('link', { name: 'Favorites' }).click();
  await expect(page).toHaveURL('/favorites');
  await page
    .getByRole('button', { name: `Delete saved search "${keyword}"` })
    .click();
  await expect(page.getByText("You haven't saved any searches yet.")).toBeVisible();
  await expect(page.getByRole('link', { name: `"${keyword}"` })).toHaveCount(0);
});
