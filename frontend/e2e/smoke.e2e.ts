import { expect, test, type APIRequestContext } from '@playwright/test';

/**
 * The seven honest smoke flows (backlog #181, money path added by #194,
 * offer + dispute paths added by #213) — see playwright.config.ts for
 * what this layer does and does not claim.
 * Selectors are role/label based (the SPA's #167 accessibility pass binds
 * every form field to a label), never CSS paths, so a renamed class cannot
 * silently green the suite and a lost label turns it red.
 *
 * State discipline: every test registers its own account(s) against the
 * fresh per-job backend, with a per-run unique suffix, so the suite is
 * re-runnable against a reused local server without collisions. The one
 * exception is the ADMIN account (flow 7): registration only ever mints
 * USER accounts, so the admin is seeded at boot by the backend's opt-in
 * bootstrap (APP_BOOTSTRAP_ADMIN_*, set only in the e2e webServer env in
 * playwright.config.ts) — the same mechanism an operator uses for a real
 * deployment's first admin, never a test-only endpoint.
 *
 * Credential-bucket arithmetic (re-derived for seven flows, #213): login
 * and register share ONE credential bucket (capacity 5 per IP, refill
 * 5/min, and a successful login resets the bucket to full — see
 * AuthRateLimitFilter). The suite totals 11 registers + 11 logins, but
 * what matters is the longest run between successful-login resets:
 *   flow 1 register + flow 2 register + flow 2 login  = 3
 *   flow 3 register + flow 3 login                    = 2
 *   flow 4 register x2 + flow 4 buyer login           = 3
 *   flow 5 register x2 + flow 5 seller login          = 3
 *   flow 5 buyer login (after the seller login reset) = 1
 *   flow 6 register x2 + flow 6 buyer login           = 3
 *   flow 6 seller login, then flow 6 buyer login      = 1 + 1
 *   flow 7 register x2 + flow 7 buyer login           = 3
 *   flow 7 admin login, then flow 7 buyer login       = 1 + 1
 * The worst run is 3 of 5, and the 1-token-per-12s refill only widens the
 * margin. Order create/pay/complete calls carry a Bearer token and never
 * touch the credential bucket; logout is not a credential endpoint either.
 */

const PASSWORD = 'E2eSmoke1234'; // satisfies the 12-char minimum + strength rules

/**
 * The seeded ADMIN account (backlog #213, flow 7). These credentials are
 * NOT registered through the API — playwright.config.ts passes the same
 * values to the backend as APP_BOOTSTRAP_ADMIN_* and
 * BootstrapAdminInitializer creates the account at boot. They exist only
 * inside the throwaway per-job e2e stack (in-memory H2, CI-only
 * browsers); the password is the suite's shared test password.
 */
const ADMIN_ACCOUNT: TestAccount = {
  username: 'e2eadmin',
  email: 'e2eadmin@example.com',
  password: PASSWORD,
};

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

/**
 * Creates an order through the public API (backlog #194 flow 5 seeds its
 * PAID order this way — the real endpoint, with the Idempotency-Key header
 * the endpoint requires, never a fixture or a test-only back door).
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
 * Pays an order through the public API and returns the buyer's one-time
 * handover code. The SPA sends no payment token, so the mock capture
 * always succeeds here (the decline path needs the documented
 * `tok_decline` test token, which this suite never sends). The pay
 * response is the only place the code is ever disclosed (#179).
 */
async function payOrderViaApi(
  request: APIRequestContext,
  buyerToken: string,
  orderId: number,
): Promise<string> {
  const res = await request.post(`/api/orders/${orderId}/pay`, {
    headers: { Authorization: `Bearer ${buyerToken}` },
    data: {},
  });
  expect(res.status(), 'API pay should succeed').toBe(200);
  const body = await res.json();
  expect(body.data.status, 'paid order carries PAID status').toBe('PAID');
  const code = body.data.handoverCode as string | undefined;
  expect(code, 'pay response discloses the handover code once').toMatch(/^\d{6}$/);
  return code as string;
}

/**
 * Reads one order back through the public API (backlog #213 flow 6 pins
 * the created order's amount this way: the Offers page announces the
 * order id and that it was created "at the agreed price", but only the
 * order resource itself proves the amount is the COUNTER price in cents,
 * not the listing's asking price).
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

async function logoutThroughUi(page: import('@playwright/test').Page): Promise<void> {
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
  page: import('@playwright/test').Page,
  orderId: number,
  status: string,
): Promise<void> {
  const row = page.locator('li', { hasText: `Order #${orderId}` });
  const orderLink = row.getByRole('link', { name: new RegExp(`Order #${orderId}\\b`) });
  await expect(orderLink).toContainText(status);
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

test('buy now, pay, and see the handover code and a PAID order', async ({ page, request }) => {
  const seller = newAccount('e2esell');
  const buyer = newAccount('e2ebuy');
  const sellerToken = await registerViaApi(request, seller);
  await registerViaApi(request, buyer);
  const title = `E2E money path textbook ${uniqueSuffix()}`;
  const itemId = await createListingViaApi(request, sellerToken, title);

  await loginThroughUi(page, buyer);

  // Browse → detail, like a real buyer (the card link, not a typed URL).
  const cardLink = page.getByRole('link', { name: title });
  await expect(cardLink).toBeVisible();
  await cardLink.click();
  await expect(page).toHaveURL(new RegExp(`/items/${itemId}$`));

  // Buy now lands on the confirmation page for the new order.
  await page.getByRole('button', { name: 'Buy now' }).click();
  await expect(page).toHaveURL(/\/orders\/\d+$/);
  await expect(page.getByRole('heading', { name: 'Order confirmed' })).toBeVisible();
  const orderId = Number(page.url().split('/').pop());

  // Paying discloses the handover code exactly once, on this page (#179):
  // a dead pay button or a lost mutation result fails here.
  await page.getByRole('button', { name: 'Pay now (demo)' }).click();
  const codeValue = page
    .locator('p', { hasText: 'Your handover code:' })
    .locator('strong');
  await expect(codeValue).toBeVisible();
  expect((await codeValue.textContent())?.trim()).toMatch(/^\d{6}$/);
  await expect(page.getByText('Paid (demo capture)')).toBeVisible();

  // …and the order reads PAID in the buyer's own history.
  await page.getByRole('link', { name: 'My orders' }).click();
  await expect(page).toHaveURL('/orders');
  await expect(page.getByRole('heading', { name: 'My orders' })).toBeVisible();
  await expectOrderRowStatus(page, orderId, 'PAID');
});

test('seller completes with the handover code and the buyer sees COMPLETED', async ({
  page,
  request,
}) => {
  const seller = newAccount('e2esell');
  const buyer = newAccount('e2ebuy');
  const sellerToken = await registerViaApi(request, seller);
  const buyerToken = await registerViaApi(request, buyer);
  const title = `E2E handover textbook ${uniqueSuffix()}`;
  const itemId = await createListingViaApi(request, sellerToken, title);
  // Seed the PAID order through the real API (no DB fixtures): the pay
  // response hands this spec the buyer's code, exactly as it hands it to
  // the buyer in flow 4. What this flow proves in the browser is the
  // seller-side completion and the buyer-side result.
  const orderId = await createOrderViaApi(request, buyerToken, itemId);
  const handoverCode = await payOrderViaApi(request, buyerToken, orderId);

  await loginThroughUi(page, seller);

  await page.getByRole('link', { name: 'Sales' }).click();
  await expect(page).toHaveURL('/seller/orders');
  await expect(
    page.getByRole('heading', { name: 'Orders on my listings' }),
  ).toBeVisible();
  await expectOrderRowStatus(page, orderId, 'PAID');

  // The seller types the buyer's code into that row's field and completes;
  // the row flips to COMPLETED on the refetch.
  const row = page.locator('li', { hasText: `Order #${orderId}` });
  await row.getByLabel(/Handover code/).fill(handoverCode);
  await row.getByRole('button', { name: 'Complete order' }).click();
  await expectOrderRowStatus(page, orderId, 'COMPLETED');

  // The buyer, in their own session, sees the same terminal state.
  await logoutThroughUi(page);
  await loginThroughUi(page, buyer);
  await page.getByRole('link', { name: 'My orders' }).click();
  await expect(page).toHaveURL('/orders');
  await expectOrderRowStatus(page, orderId, 'COMPLETED');
});

test('offer, seller counter, buyer counter-accept creates the order at the counter price — and it pays', async ({
  page,
  request,
}) => {
  const seller = newAccount('e2esell');
  const buyer = newAccount('e2ebuy');
  const sellerToken = await registerViaApi(request, seller);
  const buyerToken = await registerViaApi(request, buyer);
  const title = `E2E offer textbook ${uniqueSuffix()}`;
  // Asking price ¥19.99 (createListingViaApi's fixed 1999 cents). The
  // offer is ¥15.00 and the counter ¥17.00 — three distinct amounts, so
  // the final amount pin cannot pass on the wrong one.
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

  // The seller counters at ¥17.00 from the Offers inbox.
  await logoutThroughUi(page);
  await loginThroughUi(page, seller);
  await page.getByRole('link', { name: 'Offers' }).click();
  await expect(page).toHaveURL('/offers');
  const receivedRow = page.locator('li', { hasText: `Listing #${itemId}` });
  await expect(receivedRow).toContainText('offered ¥15.00');
  await receivedRow.getByLabel('Counter price in yuan').fill('17');
  await receivedRow.getByRole('button', { name: 'Send counter' }).click();
  await expect(receivedRow).toContainText('You countered at ¥17.00');

  // The buyer accepts the counter from their own Offers inbox.
  await logoutThroughUi(page);
  await loginThroughUi(page, buyer);
  await page.getByRole('link', { name: 'Offers' }).click();
  await expect(page).toHaveURL('/offers');
  const madeRow = page.locator('li', { hasText: `Listing #${itemId}` });
  await expect(madeRow).toContainText('The seller countered at ¥17.00');
  await madeRow.getByRole('button', { name: 'Accept counter' }).click();
  await expect(madeRow).toContainText('was created at the agreed price.');
  const madeText = (await madeRow.textContent()) ?? '';
  const orderMatch = /Order #(\d+) was created/.exec(madeText);
  expect(orderMatch, 'counter-accept announces the created order id').toBeTruthy();
  const orderId = Number(orderMatch?.[1]);

  // The end-to-end pin (#168/#193 snapshot discipline): the order exists
  // at the COUNTER price — 1700 cents, not the 1500 offered and not the
  // 1999 asking price.
  const order = await getOrderViaApi(request, buyerToken, orderId);
  expect(order.amountCents).toBe(1700);
  expect(order.status).toBe('PENDING');

  // …and the counter-priced order pays like any other: the confirmation
  // page shows ¥17.00, pay discloses the handover code, and My orders
  // reads PAID.
  await page.goto(`/orders/${orderId}`);
  await expect(page.getByRole('heading', { name: 'Order confirmed' })).toBeVisible();
  await expect(page.getByText('¥17.00')).toBeVisible();
  await page.getByRole('button', { name: 'Pay now (demo)' }).click();
  await expect(page.getByText('Paid (demo capture)')).toBeVisible();
  await page.getByRole('link', { name: 'My orders' }).click();
  await expect(page).toHaveURL('/orders');
  await expectOrderRowStatus(page, orderId, 'PAID');
});

test('buyer disputes a paid order, admin resolves with a refund, and the buyer sees REFUNDED', async ({
  page,
  request,
}) => {
  const seller = newAccount('e2esell');
  const buyer = newAccount('e2ebuy');
  const sellerToken = await registerViaApi(request, seller);
  const buyerToken = await registerViaApi(request, buyer);
  const title = `E2E dispute textbook ${uniqueSuffix()}`;
  const itemId = await createListingViaApi(request, sellerToken, title);
  // Seed the PAID order through the real API (the flow 5 mould): what
  // this flow proves in the browser is the dispute, the ADMIN resolution
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
  await buyerRow.getByLabel('What went wrong?').fill('The textbook arrived with a torn cover.');
  await buyerRow.getByRole('button', { name: 'Open dispute' }).click();
  await expect(buyerRow).toContainText('Dispute open — this order is frozen');
  await expectOrderRowStatus(page, orderId, 'DISPUTED');

  // The seeded ADMIN resolves the dispute with a refund from the admin
  // queue; the row records the refund resolution.
  await logoutThroughUi(page);
  await loginThroughUi(page, ADMIN_ACCOUNT);
  await page.getByRole('link', { name: 'Admin disputes' }).click();
  await expect(page).toHaveURL('/admin/disputes');
  await expect(page.getByRole('heading', { name: 'Admin disputes' })).toBeVisible();
  const disputeRow = page.locator('tr', { hasText: `Order #${orderId}` });
  await expect(disputeRow).toContainText('OPEN');
  await disputeRow.getByRole('button', { name: 'Resolve with refund' }).click();
  await expect(disputeRow).toContainText('RESOLVED_REFUND');

  // The buyer, in their own session, sees the terminal REFUNDED state.
  await logoutThroughUi(page);
  await loginThroughUi(page, buyer);
  await page.getByRole('link', { name: 'My orders' }).click();
  await expect(page).toHaveURL('/orders');
  await expectOrderRowStatus(page, orderId, 'REFUNDED');
});
