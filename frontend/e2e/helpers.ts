import { expect, type APIRequestContext, type Page } from '@playwright/test';

/**
 * Shared helpers for the browser e2e smoke suite (backlog #181, split
 * by #253): smoke.e2e.ts holds the seven original flows, checkout.e2e.ts
 * holds the cart checkout journey, and both import from here so the
 * account/registration/login/row-status moulds stay identical. Nothing
 * here seeds state through a back door — registration and listing
 * creation go through the public API, exactly as the specs did when
 * these helpers lived inline in smoke.e2e.ts.
 *
 * Credential-bucket note (shared by both spec files, re-derived per
 * file in its own header): login and register share ONE bucket
 * (capacity 5 per IP, refill 5/min, a successful login resets it to
 * full — see AuthRateLimitFilter). The specs run serially in one
 * worker against one backend, and every spec's run sequence ends on
 * a login reset, so no file's worst run stacks on the previous file's.
 */

const PASSWORD = 'E2eSmoke1234'; // satisfies the 12-char minimum + strength rules

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.floor(Math.random() * 1_000_000).toString(36)}`;
}

interface TestAccount {
  username: string;
  email: string;
  password: string;
}

function newAccount(prefix: string): TestAccount {
  const suffix = uniqueSuffix();
  return {
    username: `${prefix}${suffix}`.slice(0, 60),
    email: `${prefix}${suffix}@example.com`,
    password: PASSWORD,
  };
}

/**
 * Registers through the public API (same origin, through the preview
 * proxy) and returns the access token. With the e2e stack's verification
 * gate stubbed off, registration answers the session token pair.
 */
async function registerViaApi(
  request: APIRequestContext,
  account: TestAccount,
): Promise<string> {
  const res = await request.post('/api/auth/register', {
    data: {
      username: account.username,
      email: account.email,
      password: account.password,
    },
  });
  expect(res.status(), 'API register should succeed in the e2e stack').toBe(200);
  const body = await res.json();
  const token = body?.data?.accessToken as string | undefined;
  expect(token, 'register (gate off) returns an access token').toBeTruthy();
  return token as string;
}

async function createListingViaApi(
  request: APIRequestContext,
  token: string,
  title: string,
): Promise<number> {
  const res = await request.post('/api/items', {
    headers: { Authorization: `Bearer ${token}` },
    data: { title, priceCents: 1999 },
  });
  expect(res.status(), 'API create listing should succeed').toBe(200);
  const body = await res.json();
  return body.data.id as number;
}

async function loginThroughUi(page: Page, account: TestAccount): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Username or email').fill(account.username);
  await page.getByLabel('Password').fill(account.password);
  await page.getByRole('button', { name: 'Login', exact: true }).click();
  await expect(page).toHaveURL('/');
  await expect(page.getByRole('heading', { name: 'Latest listings' })).toBeVisible();
}

async function logoutThroughUi(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Logout' }).click();
  await expect(page).toHaveURL('/');
  await expect(page.getByRole('link', { name: 'Login' })).toBeVisible();
}

/**
 * Asserts the status chip on an order row. The chip lives inside the
 * row's order link (next to the "Order #id" label) — asserting there,
 * rather than on the whole row, keeps the receipt sheet's own PAID /
 * status text on My orders from satisfying (or multiplying) the match.
 */
async function expectOrderRowStatus(
  page: Page,
  orderId: number,
  status: string,
): Promise<void> {
  const row = page.locator('li', { hasText: `Order #${orderId}` });
  const orderLink = row.getByRole('link', { name: new RegExp(`Order #${orderId}\\b`) });
  await expect(orderLink).toContainText(status);
}

export {
  createListingViaApi,
  expectOrderRowStatus,
  loginThroughUi,
  logoutThroughUi,
  newAccount,
  registerViaApi,
  uniqueSuffix,
  type TestAccount,
};

// The seeded ADMIN account in smoke.e2e.ts shares this throwaway test
// password (it is NOT registered through the API — see that file).
export { PASSWORD };
