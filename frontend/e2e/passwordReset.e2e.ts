import { expect, test, type APIRequestContext } from '@playwright/test';
import {
  loginThroughUi,
  newAccount,
  PASSWORD,
  registerViaApi,
  type TestAccount,
} from './helpers';

/**
 * The password-reset journey (backlog #399) — the sixteenth flow of
 * the browser e2e smoke suite: a user who forgot their password
 * requests a reset from the login page, reads the mailed token from
 * the test-only capture seam (backlog #407 — the default sender is
 * log-only with the token redacted, so no test could ever read it
 * from a mailbox, and this suite does not pretend one exists),
 * sets a new password on the reset page, and logs in with it. The
 * fifteen earlier flows live in smoke.e2e.ts (seven),
 * checkout.e2e.ts (cart checkout), messaging.e2e.ts (buyer–seller
 * messaging), moderation.e2e.ts (listing report → admin action),
 * favorites.e2e.ts (favorites + seller-follow),
 * savedSearches.e2e.ts (saved-searches), mailedSale.e2e.ts (mailed
 * sale), appeals.e2e.ts (seller appeal) and payouts.e2e.ts
 * (seller payout); the shared account/API/login moulds live in
 * helpers.ts. Selectors are role/label based, like the rest of the
 * suite, never CSS paths.
 *
 * What this flow proves end to end, in the browser, that no earlier
 * flow covers:
 * - the forgot-password page (#99): the "Forgot password?" link on
 *   /login leads to the request form, and submitting it renders the
 *   identical "check for your reset link" state for an existing
 *   account — the page never claims an email was sent;
 * - the reset-token seam (#407): the token the request issued is
 *   readable through GET /api/test/auth/password-reset-token for
 *   the account's user id, and only there — the request response
 *   itself carries no token, by design;
 * - the reset page (#99): /reset-password?token=… accepts the new
 *   password and lands on /login with the signed-out-everywhere
 *   note;
 * - the login with the new password succeeds in the browser, and
 *   the reset's kill semantics hold — pinned through the API at
 *   each step, so a green UI confirmation alone cannot pass this
 *   flow: the pre-reset access token 401s on GET /api/auth/me
 *   (token_version bump, backlog #59) and the old password 401s
 *   on POST /api/auth/login.
 *
 * What it does NOT cover, stated plainly: the 30-minute token
 * expiry (the flow confirms within seconds — expiry stays
 * unit-covered); the invalid/expired-token 401 copy on the reset
 * page and the weak-password 400 mapping (both RTL-covered in
 * ResetPasswordPage.test.tsx, backlog #99); the breached-password
 * 400 and the reuse-guard 400 on confirm (unit-covered, backlogs
 * #197/#139); and the reset request for an unknown identifier
 * (the identical-200, no-oracle behaviour is MockMvc-covered in
 * #90). The account is seeded through the real API (register);
 * only the request, the reset and the logins are browser-driven,
 * the flow-4 mould.
 *
 * Credential-bucket arithmetic (this file's own runs; the bucket is
 * shared per IP with the other specs, but the files run serially and
 * the #373 bypass is on for the whole suite — see
 * playwright.config.ts — so the arithmetic below is pre-bypass
 * history, recorded for the same reason the other specs record
 * theirs): 1 register + 2 logins (one UI login with the new
 * password, one API login attempt with the old password that 401s).
 * The reset request and the confirm share their own 5/min-per-IP
 * reset bucket (one call each here) and the request also consumes
 * one token of the 5/hour per-address mail bucket (backlog #354) —
 * both far under their ceilings.
 *
 * Stack note: like the other fifteen flows, this spec runs in the
 * existing CI `e2e` job (vite preview + backend jar on H2, picked up
 * automatically by the config's `**\/*.e2e.ts` match) — no new job,
 * no second stack. The docker-compose job stays curl-only.
 */

/** The new password the reset sets — 12 chars, letters + digits, unlike the old one. */
const NEW_PASSWORD = 'E2eSmoke5678';

/**
 * Reads the caller's user id through GET /api/auth/me with a Bearer
 * token. The register response carries no id, and the capture seam
 * below is keyed on it.
 */
async function userIdForToken(request: APIRequestContext, token: string): Promise<number> {
  const res = await request.get('/api/auth/me', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.status(), 'API me should succeed for the fresh register token').toBe(200);
  const body = await res.json();
  const id = body?.data?.id as number | undefined;
  expect(id, 'me should carry the user id').toBeTruthy();
  return id as number;
}

/**
 * Reads the latest captured reset token for a user from the test-only
 * seam (backlog #407): GET /api/test/auth/password-reset-token.
 * Public at the security chain — the capture knob, not a Bearer
 * token, is the gate — and the knob is on only on this throwaway
 * e2e backend. The token is 32 random bytes, Base64-URL encoded
 * without padding (43 chars); the shape is pinned so a seam that
 * started returning something else fails here, not silently at the
 * confirm step.
 */
async function capturedResetToken(
  request: APIRequestContext,
  userId: number,
): Promise<string> {
  const res = await request.get(`/api/test/auth/password-reset-token?userId=${userId}`);
  expect(res.status(), 'API reset-token seam should have a token for this user').toBe(200);
  const body = await res.json();
  const token = body?.data?.token as string | undefined;
  expect(token, 'seam should return a captured reset token').toMatch(/^[A-Za-z0-9_-]{43}$/);
  return token as string;
}

test('password reset journey: forgot-password, seam token, reset, login with the new password', async ({
  page,
  request,
}) => {
  const account: TestAccount = newAccount('e2ereset');
  const oldToken = await registerViaApi(request, account);
  const userId = await userIdForToken(request, oldToken);

  // The request, from the login page like a real user who forgot
  // their password: the link leads to the form, and submitting the
  // account's username renders the identical success state.
  await page.goto('/login');
  await page.getByRole('link', { name: 'Forgot password?' }).click();
  await expect(page).toHaveURL('/forgot-password');
  await expect(page.getByRole('heading', { name: 'Forgot password' })).toBeVisible();
  await page.getByLabel('Username or email').fill(account.username);
  await page.getByRole('button', { name: 'Request reset link' }).click();
  await expect(page.getByRole('heading', { name: 'Check for your reset link' })).toBeVisible();
  await expect(
    page.getByText('If an account exists for that username or email, a reset link was issued.'),
  ).toBeVisible();

  // The token the request issued, read from the test-only seam —
  // never from a mailbox, and never from any API response.
  const token = await capturedResetToken(request, userId);

  // The reset page, reached with the captured token: the new
  // password lands, and the page sends the user to /login with the
  // signed-out-everywhere note.
  await page.goto(`/reset-password?token=${encodeURIComponent(token)}`);
  await expect(page.getByRole('heading', { name: 'Reset password' })).toBeVisible();
  await page.getByLabel('New password', { exact: true }).fill(NEW_PASSWORD);
  await page.getByLabel('Confirm new password', { exact: true }).fill(NEW_PASSWORD);
  await page.getByRole('button', { name: 'Reset password' }).click();
  await expect(page).toHaveURL('/login');
  await expect(
    page.getByText(
      'Password reset complete. You have been signed out everywhere — log in with your new password.',
    ),
  ).toBeVisible();

  // The new password logs in, in the browser.
  await loginThroughUi(page, { ...account, password: NEW_PASSWORD });

  // The reset's kill semantics, pinned through the API: the
  // pre-reset access token is dead (token_version bump), and the
  // old password no longer authenticates.
  const meWithOldToken = await request.get('/api/auth/me', {
    headers: { Authorization: `Bearer ${oldToken}` },
  });
  expect(
    meWithOldToken.status(),
    'pre-reset access token should 401 after the reset',
  ).toBe(401);
  const loginWithOldPassword = await request.post('/api/auth/login', {
    data: { usernameOrEmail: account.username, password: PASSWORD },
  });
  expect(loginWithOldPassword.status(), 'old password should 401 after the reset').toBe(401);
});
