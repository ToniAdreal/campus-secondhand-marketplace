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
 * - The dispute flow (#213) needs an ADMIN, and registration only ever
 *   mints USER accounts. The backend therefore also runs with its opt-in
 *   first-admin bootstrap (APP_BOOTSTRAP_ADMIN_*, backlog #213) pointing
 *   at a throwaway account that exists only in this stack's in-memory
 *   H2: BootstrapAdminInitializer creates it at boot from exactly these
 *   env values, the spec signs in with the same credentials, and nothing
 *   about the mechanism is e2e-specific — it is the supported way to seed
 *   a real deployment's first admin too.
 * - Browsers are downloaded in CI only (`npx playwright install`). This
 *   repo's local sandbox has no Docker and no browsers, so locally the
 *   config + test list are checked (`npx playwright test --list`) and the
 *   unit suite + build stay the local gate; the flows themselves run in
 *   the CI `e2e` job.
 *
 * Discipline:
 * - Exactly eight flows (the seven in smoke.e2e.ts — register,
 *   browse→detail, login→create→my-listings, plus the money path from
 *   backlog #194: buy→pay→handover code→PAID, and seller completes with
 *   the code→buyer sees COMPLETED; plus the two money paths from
 *   backlog #213: offer→counter→counter-accept→order at the counter
 *   price→pay, and pay→dispute→admin resolves refund→buyer sees
 *   REFUNDED — plus the cart checkout journey from backlog #253 in
 *   checkout.e2e.ts: add to cart → cart checkout → pay → seller
 *   completes with the handover code → buyer reviews).
 *   This is a smoke net, not a second unit suite.
 * - retries: 0 and workers: 1 — a flaky test is fixed or deleted, never
 *   retried into green, and the eight flows share one backend (one H2
 *   database, per-IP auth rate-limit buckets), so they run serially. The
 *   credential-bucket arithmetic for the seven smoke flows is re-derived in the
 *   header of e2e/smoke.e2e.ts (worst run between login resets: 3 of 5),
 *   and the checkout journey's runs in the header of e2e/checkout.e2e.ts.
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
        // First-admin bootstrap for the dispute flow — see header. These
        // must match ADMIN_ACCOUNT in e2e/smoke.e2e.ts; the account lives
        // only in this stack's in-memory H2 database.
        APP_BOOTSTRAP_ADMIN_USERNAME: 'e2eadmin',
        APP_BOOTSTRAP_ADMIN_EMAIL: 'e2eadmin@example.com',
        APP_BOOTSTRAP_ADMIN_PASSWORD: 'E2eSmoke1234',
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
