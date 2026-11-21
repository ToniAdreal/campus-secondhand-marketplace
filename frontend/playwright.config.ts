import { defineConfig, devices } from '@playwright/test';

/**
 * Browser end-to-end smoke (backlog #181).
 *
 * What this layer proves — and what it does not:
 * - It drives a REAL Chromium against the REAL built SPA (`vite preview`
 *   serving `dist/`) and the REAL backend jar on its default H2 profile,
 *   wired together by the same `/api` proxy shape the production nginx
 *   config uses (see the `preview.proxy` block in vite.config.ts). Before
 *   this, CI proved the layers separately (backend unit/IT, frontend
 *   unit/build, and a curl-level compose smoke with no browser and no SPA
 *   behaviour), so a broken route, a dead 401-refresh queue or a blank
 *   page could ship green.
 * - The backend runs with the email-verification gate stubbed OFF via its
 *   documented env knob (APP_AUTH_EMAIL_VERIFICATION_ENABLED=false). The
 *   default mail sender is log-only with the token redacted, so no test
 *   could ever read a verification link; the suite does not pretend a
 *   mailbox exists. With the gate off, registration returns a session —
 *   exactly the path the first flow exercises.
 * - The password-reset flow (#399) needs its mailed token, and the
 *   default sender logs with the token redacted, so no test could ever
 *   read it — the backend therefore also runs with the test-only
 *   reset-token capture ON (APP_AUTH_PASSWORD_RESET_E2E_TOKEN_CAPTURE_ENABLED=true,
 *   backlog #407, step C1 unblocking #399): the capturing sender records
 *   the latest raw token per user in memory and the test-only endpoint
 *   GET /api/test/auth/password-reset-token serves it back. The knob is
 *   set only on this throwaway e2e backend, never on a real deployment:
 *   with it on, anyone who can reach the backend can read a live reset
 *   token for any user id.
 * - The dispute flow (#213) needs an ADMIN, and registration only ever
 *   mints USER accounts. The backend therefore also runs with its opt-in
 *   first-admin bootstrap (APP_BOOTSTRAP_ADMIN_*, backlog #213) pointing
 *   at a throwaway account that exists only in this stack's in-memory
 *   H2: BootstrapAdminInitializer creates it at boot from exactly these
 *   env values, the spec signs in with the same credentials, and nothing
 *   about the mechanism is e2e-specific — it is the supported way to seed
 *   a real deployment's first admin too.
 * - The backend also runs with the credential rate-limit bypass ON
 *   (APP_AUTH_RATE_LIMIT_E2E_BYPASS=true, backlog #373). The seventeen
 *   flows share one backend and one client IP (127.0.0.1), so their
 *   register/login bursts exhaust the production 5/minute credential
 *   bucket mid-suite (main run 42734704718: register expected 200, got
 *   429) even though each flow on its own stays under the ceiling — the
 *   per-flow worst case of 3 of 5 between successful-login resets does
 *   not survive seventeen flows back to back on a 1-token-per-12-seconds
 *   refill. The bypass lifts login, register and refresh together (they
 *   share the filter's credential-bucket gate); every other limiter
 *   keeps its own bucket, and the production bucket is unchanged. The
 *   bypass is set only on this throwaway e2e backend, never on a real
 *   deployment.
 * - Browsers are downloaded in CI only (`npx playwright install`). This
 *   repo's local sandbox has no Docker and no browsers, so locally the
 *   config + test list are checked (`npx playwright test --list`) and the
 *   unit suite + build stay the local gate; the flows themselves run in
 *   the CI `e2e` job.
 *
 * Discipline:
 * - Exactly seventeen flows plus the color-contrast pass (the seven in smoke.e2e.ts — register,
 *   browse→detail, login→create→my-listings, plus the money path from
 *   backlog #194: buy→pay→handover code→PAID, and seller completes with
 *   the code→buyer sees COMPLETED; plus the two money paths from
 *   backlog #213: offer→counter→counter-accept→order at the counter
 *   price→pay, and pay→dispute→admin resolves refund→buyer sees
 *   REFUNDED — plus the cart checkout journey from backlog #253 in
 *   checkout.e2e.ts: add to cart → cart checkout → pay → seller
 *   completes with the handover code → buyer reviews — plus the
 *   messaging flow from backlog #342 in messaging.e2e.ts: buyer
 *   messages → seller replies → buyer sees the reply, and a third
 *   user cannot open the thread — plus the moderation flow from
 *   backlog #346 in moderation.e2e.ts: buyer reports → admin acts
 *   on the report → the listing shows WITHDRAWN and stops offering
 *   purchase, and a dismissed report leaves its listing AVAILABLE —
 *   plus the favorites + seller-follow flow from backlog #352 in
 *   favorites.e2e.ts: buyer favorites a listing from its detail
 *   page → it appears on /favorites → unfavorites it back off,
 *   then follows the listing's seller and the follow state survives
 *   a reload, and a guest sees no favorite control while /favorites
 *   bounces to /login — plus the saved-searches flow from backlog
 *   #359 in savedSearches.e2e.ts: buyer filters the browse page by
 *   a keyword → saves the search → it appears in the saved-searches
 *   section on /favorites with its match count → running it lands
 *   back on the exact browse URL → deleting it removes it, and a
 *   browse page with no filter active offers no save control — plus
 *   the mailed-sale flow from backlog #370 in
 *   mailedSale.e2e.ts: buy-now → pay → the seller ships with a
 *   carrier + tracking number → the buyer confirms receipt → the
 *   order reads COMPLETED and the listing flips to SOLD, pinned
 *   through the API — plus the seller-appeal flow from backlog #394 in
 *   appeals.e2e.ts: buyer reports → admin withdraws the listing →
 *   the seller appeals from My listings → the admin upholds the
 *   appeal and the listing returns to AVAILABLE, and a rejected
 *   appeal leaves its listing WITHDRAWN, pinned through the API —
 *   plus the seller-payout flow from backlog #398 in
 *   payouts.e2e.ts: buy-now → pay → the seller completes with the
 *   handover code → the seller watches the payout row go PENDING →
 *   PAYABLE on /seller/payouts → the ADMIN marks it PAID on
 *   /admin/payments → the seller's totals reflect it, pinned
 *   through the API — plus the password-reset flow from backlog #399 in
 *   passwordReset.e2e.ts: forgot-password → the token from the
 *   test-only capture seam → reset → login with the new password,
 *   pinned through the API (the pre-reset token and the old password
 *   both 401) — plus the dispute resolve-release flow from backlog
 *   #411 in disputeRelease.e2e.ts: pay → buyer disputes → the ADMIN
 *   releases the order in /admin/disputes → the buyer sees PAID again
 *   in their own session, pinned through the API — plus the
 *   color-contrast pass from backlog #369 in
 *   colorContrast.e2e.ts: axe runs the color-contrast rule only over
 *   nine key pages in both themes (light + dark). The pass is a pass,
 *   not an eighteenth flow — the mailed-sale journey in #370 is the
 *   thirteenth, the seller-appeal journey in #394 is the fourteenth,
 *   the seller-payout journey in #398 is the fifteenth, the
 *   password-reset journey in #399 is the sixteenth, and the dispute
 *   resolve-release journey in #411 is the seventeenth — and it
 *   proves contrast, not journeys).
 *   This is a smoke net, not a second unit suite.
 * - retries: 0 and workers: 1 — a flaky test is fixed or deleted, never
 *   retried into green, and the seventeen flows share one backend (one H2
 *   database), so they run serially. The credential-bucket arithmetic in
 *   the per-spec headers is pre-bypass history (backlog #373): the
 *   bypass, not the 3-of-5 arithmetic, is what keeps register/login from
 *   429ing now.
 * - The payout sweep runs every 10 seconds in this stack and the
 *   dispute window is 30 seconds (APP_PAYOUTS_SWEEP_CRON +
 *   APP_DISPUTES_WINDOW, backlog #406, step A1 unblocking the #398
 *   payout flow): promotion needs completedAt + window <= now and the
 *   only promoter is the sweep, so neither knob alone reaches PAYABLE
 *   inside a 60 s test — the hourly sweep and the 7-day window are the
 *   production defaults and stay untouched. The dispute flow (#213)
 *   still passes: it opens its dispute seconds after pay, inside the
 *   30 s window, and resolution is not window-gated; no other flow
 *   asserts payout state, so mid-suite promotions are harmless. The
 *   #398 flow itself runs with a 120 s per-test timeout (its own
 *   `test.setTimeout`, the only spec that raises the 60 s default):
 *   the promotion wait is 30 s of window plus up to 10 s of sweep
 *   cadence after completion, so the journey's floor is ~40 s after
 *   the order completes and the ADMIN + seller tails do not fit in
 *   60 s; the knobs stay as set here.
 * - The CI job builds the backend jar with tests skipped ON PURPOSE: the
 *   backend-unit / backend-integration jobs already gate the backend test
 *   suites on the same push; here the jar is only a fixture.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.e2e.ts',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  outputDir: 'test-results',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: [
    {
      // cwd defaults to this config's directory (frontend/), so the jar
      // path is relative to the repo layout. The CI e2e job packages the
      // jar before invoking Playwright.
      name: 'backend',
      command: 'java -jar ../backend/target/campus-marketplace-0.1.0.jar',
      url: 'http://127.0.0.1:8080/actuator/health',
      reuseExistingServer: !process.env.CI,
      timeout: 180_000,
      env: {
        // Verification stubbed off for the e2e stack only — see header.
        APP_AUTH_EMAIL_VERIFICATION_ENABLED: 'false',
        // Credential rate-limit bypass for the e2e stack only — see header.
        APP_AUTH_RATE_LIMIT_E2E_BYPASS: 'true',
        // First-admin bootstrap for the dispute flow — see header. These
        // must match ADMIN_ACCOUNT in e2e/smoke.e2e.ts; the account lives
        // only in this stack's in-memory H2 database.
        APP_BOOTSTRAP_ADMIN_USERNAME: 'e2eadmin',
        APP_BOOTSTRAP_ADMIN_EMAIL: 'e2eadmin@example.com',
        APP_BOOTSTRAP_ADMIN_PASSWORD: 'E2eSmoke1234',
        // Payout sweep cadence + dispute window for the payout flow —
        // see header (backlog #406). E2e stack only; production keeps
        // the hourly sweep and the 7-day window.
        APP_PAYOUTS_SWEEP_CRON: '*/10 * * * * *',
        APP_DISPUTES_WINDOW: 'PT30S',
        // Reset-token capture for the password-reset flow — see header
        // (backlog #407, step C1 unblocking #399). E2e stack only.
        APP_AUTH_PASSWORD_RESET_E2E_TOKEN_CAPTURE_ENABLED: 'true',
      },
    },
    {
      name: 'frontend-preview',
      command: 'npm run build && npm run preview -- --host 127.0.0.1 --port 4173 --strictPort',
      url: 'http://127.0.0.1:4173',
      reuseExistingServer: !process.env.CI,
      timeout: 180_000,
    },
  ],
});
