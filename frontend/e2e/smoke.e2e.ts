import { expect, test, type APIRequestContext } from '@playwright/test';

/**
 * The three honest smoke flows (backlog #181) — see playwright.config.ts
 * for what this layer does and does not claim. Selectors are role/label
 * based (the SPA's #167 accessibility pass binds every form field to a
 * label), never CSS paths, so a renamed class cannot silently green the
 * suite and a lost label turns it red.
 *
 * State discipline: every test registers its own account(s) against the
 * fresh per-job backend, with a per-run unique suffix, so the suite is
 * re-runnable against a reused local server without collisions. Auth
 * calls stay inside the backend's rate limits: login and register share
 * ONE credential bucket (5/min per IP, and a successful login resets
 * it) — the suite makes 3 registers + 2 logins total, and the two
 * successful logins land between the registers, so the bucket never
 * holds more than 2 attempts.
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

async function loginThroughUi(
  page: import('@playwright/test').Page,
  account: TestAccount,
): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Username or email').fill(account.username);
  await page.getByLabel('Password').fill(account.password);
  await page.getByRole('button', { name: 'Login', exact: true }).click();
  await expect(page).toHaveURL('/');
  await expect(page.getByRole('heading', { name: 'Latest listings' })).toBeVisible();
}

test('register lands on browse signed in', async ({ page }) => {
  const account = newAccount('e2ereg');

  await page.goto('/register');
  await expect(page.getByRole('heading', { name: 'Register' })).toBeVisible();
  await page.getByLabel('Username').fill(account.username);
  await page.getByLabel('Email').fill(account.email);
  await page.getByLabel('Password').fill(account.password);
  await page.getByRole('button', { name: 'Create account' }).click();

  // Gate stubbed off in this stack: registration returns a session and the
  // SPA navigates home signed in (username + Logout in the nav).
  await expect(page).toHaveURL('/');
  await expect(page.getByRole('heading', { name: 'Latest listings' })).toBeVisible();
  await expect(page.getByText(account.username)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Logout' })).toBeVisible();
});

test('browse opens a listing detail page', async ({ page, request }) => {
  const seller = newAccount('e2esell');
  const token = await registerViaApi(request, seller);
  const title = `E2E detail textbook ${uniqueSuffix()}`;
  const itemId = await createListingViaApi(request, token, title);

  await loginThroughUi(page, seller);

  // The seeded listing renders on browse and its card link routes to the
  // detail page (a broken route or blank page fails here, not in unit).
  const cardLink = page.getByRole('link', { name: title });
  await expect(cardLink).toBeVisible();
  await cardLink.click();

  await expect(page).toHaveURL(new RegExp(`/items/${itemId}$`));
  await expect(page.getByRole('heading', { name: title })).toBeVisible();
});

test('login, create a listing, and see it under My listings', async ({ page, request }) => {
  const account = newAccount('e2ecreate');
  await registerViaApi(request, account);

  await loginThroughUi(page, account);

  await page.getByRole('link', { name: 'Sell an item' }).click();
  await expect(page).toHaveURL(/\/items\/new$/);
  await expect(page.getByRole('heading', { name: 'Sell an item' })).toBeVisible();

  const title = `E2E created listing ${uniqueSuffix()}`;
  await page.getByLabel('Title').fill(title);
  await page.getByLabel('Price (USD)').fill('19.99');
  await page.getByRole('button', { name: 'Publish listing' }).click();

  // Create navigates to the new listing's detail page…
  await expect(page).toHaveURL(/\/items\/\d+$/);
  await expect(page.getByRole('heading', { name: title })).toBeVisible();

  // …and the seller's own list carries it too.
  await page.getByRole('link', { name: 'My listings' }).click();
  await expect(page).toHaveURL('/my-listings');
  await expect(page.getByRole('heading', { name: 'My listings' })).toBeVisible();
  await expect(page.getByText(title)).toBeVisible();
});
