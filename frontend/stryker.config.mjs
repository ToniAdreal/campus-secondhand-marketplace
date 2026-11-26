/**
 * Frontend mutation testing (backlog #252, widened by #263, #280,
 * #287 and #322), mirroring the backend PIT pilot (backlog #192,
 * docs/mutation.md) and the #165 ratchet discipline.
 *
 * Scope is deliberately narrow on purpose: the three store modules
 * below are zustand stores with focused unit suites, so a mutant is
 * killed by an assertion about behaviour, not by a page failing to
 * render. The cart store (`src/store/useCartStore.ts`) is the purest
 * store module plus pure helpers (`deriveCartIdempotencyKey`,
 * `cartTotalCents`, `revalidateCartItems`, `checkoutCart`); the auth
 * store (`src/store/useAuthStore.ts`, added by #263) is the
 * auth-direction core — token-in-memory + refresh-queue semantics
 * via the client interceptor, login/logout/hydrate with the TanStack
 * cache clear; the theme store (`src/store/useThemeStore.ts`, added
 * by #280) is the last remaining zustand store — preference init
 * from storage plus `setPreference` persist + apply in one step.
 * The #287 entries are NOT whole api files: each is a Stryker
 * mutation range (`file:startLine-endLine`) covering exactly one
 * `describe*Error` mapper function, so the hooks, queries and
 * mutations that share those api modules are never mutated — only
 * the 36 pure error-copy mapper ranges (messages, categories,
 * orders ×9, items ×4, admin ×8, reports ×2, offers, disputes ×3,
 * appeals ×2, reviews ×3, auth step-up, client request) are in
 * scope. (The admin ×8 is ranges, not mappers: 7 `describe*Error`
 * mappers plus the pre-existing 489-505 `useAdminPaymentAttempts`
 * hook range.) The ranges
 * were taken from the TypeScript AST (function start/end lines), not
 * hand-counted, so a mapper that moves lines must have its range
 * re-derived. Widening the scope further still requires
 * re-measuring the floor first.
 *
 * How it runs: Stryker with the Vitest runner. The runner always uses
 * perTest coverage analysis and, by default, only runs tests related to
 * the mutated file, so one mutant costs the mutated file's suite, not
 * the whole ~1000-test suite. The config is NOT part of the default
 * local `npm test` path: run `npm run test:mutation` explicitly, or let
 * the dedicated `frontend-mutation` CI job run it.
 *
 * Score and floor: `thresholds.break` is the gate — Stryker fails the
 * run when the mutation score falls below it. The floor is set from a
 * real measured run, rounded DOWN to a whole percent, never above the
 * measured score (see docs/mutation-frontend.md for the runs, the
 * scores and the surviving mutants). The cart-store floor was 90 (the
 * #252 measured 90.78 over 141 mutants, floored); #263 measured the
 * auth store separately first (break 0) and then re-ran the combined
 * scope at break 90 to prove the gate still passes with the widened
 * scope — the cart-store floor is unchanged, not lowered to fit the
 * new scope. #280 did the same for the theme store: measured
 * separately first (break 0, 100.00% over 5 mutants, 0 survivors),
 * then re-ran the combined scope at break 90 — the floor stays 90.
 * #287 measured the api error mappers separately first (break 0,
 * over the 34 mapper ranges only: 75.72%, 951/1256), then killing
 * tests closed the gap and the combined scope was re-measured at
 * break 90 on 2026-11-01 (head 1483e47, isolated clone, 27m35s):
 * 93.53% (1329/1421, 60 survived, 32 no-coverage), so the mapper
 * ranges below are a LANDED scope and the floor binds
 * (see docs/mutation-frontend.md, "Fifth measured run").
 * #322 extends the same range discipline to the social-graph +
 * saved-search api modules: each range below is exactly one exported
 * hook function (start = the `export function` line, end = its
 * closing brace), covering the queryFn malformed-payload fallback,
 * the mutationFn and the onSuccess invalidation — not the key
 * constants, interfaces, or the saved-searches pure helpers
 * (`savedSearchBrowsePath`, `describeSavedSearch`,
 * `centsToYuanParam`), which are not hook paths. The ranges were
 * taken from the current files at implementation time, not
 * hand-counted from memory, so a hook that moves lines must have
 * its range re-derived. The #322 scope is measured separately
 * first (break 0) and lands only if the combined score still
 * clears the break-90 floor (see docs/mutation-frontend.md,
 * "#322 measured runs").
 * #327 extends the same discipline to the payouts, sellers,
 * telemetry and webauthn api modules: each range below is exactly
 * one exported function — the two payout hooks (including the
 * `useSellerPayoutsInfinite` getNextPageParam), the two seller
 * hooks, the three telemetry functions (route template, beacon,
 * install), and the seven webauthn pure functions. Key constants,
 * interfaces and private helpers (`templateSegments`,
 * `matchesTemplate`, `errorNameOf`, `toDescriptor`, `bufferOf`)
 * are not in scope. The #327 scope was measured separately first
 * (break 0, 65.30%); killing tests closed the gap to 78.54%
 * before landing, and the combined score still clears the
 * break-90 floor (see docs/mutation-frontend.md, "#327 measured
 * runs"). No production-code change: no mutant exposed a real bug.
 * #347 extends the same discipline to the notifications +
 * recently-viewed api modules: each range below is exactly one
 * exported hook — the eight notifications hooks (`useNotifications`,
 * `useUnreadNotificationCount`, `useNotificationStream` with its
 * bounded reconnect, `useNotificationPreferences`,
 * `useSetNotificationPreference`, `useMarkAllNotificationsRead`,
 * `useDeleteNotification`, `useMarkNotificationRead`) and
 * `useRecentlyViewed`. Key constants (`notificationKeys`,
 * `recentlyViewedKeys`, the reconnect cap constants) and the pure
 * `describeNotification` copy function are not in scope. The #347
 * scope was measured separately first (break 0, 82.08% over 106
 * mutants, counting the 2 timeouts as scored) and the combined
 * score still clears the break-90 floor (see
 * docs/mutation-frontend.md, "#347 measured runs"). No
 * production-code change: no mutant exposed a real bug.
 * #351 re-derived 21 ranges from the current TypeScript AST after
 * the #288 axios-to-fetch migration moved the functions (categories
 * x1, orders x9, admin x6, client x1, webauthn x4): the old ranges
 * were silently mutating the wrong code (orders.ts:398-419 covered
 * the useCreateOrderWithKey hook, not a mapper) and the instrumented
 * total had fallen from 1830 to 1566. The re-derivation is a pure
 * line-shift correction - the same functions, in the same order,
 * with the same line counts - verified by instrument-count probes
 * before the combined run. No production-code change.
 * #351 measured the combined scope at break 90 on 2026-11-07
 * (head a684262, isolated clone, systemd unit, 57m51s):
 * 91.37% (1684/1843 scored: 1683 killed + 1 timeout, 126
 * survived, 33 no-coverage), so the floor ratchets 90 -> 91
 * (floor of the measured score, never above it) and the
 * re-derived ranges above are a LANDED scope (see
 * docs/mutation-frontend.md, "Ninth measured run"). The #322
 * survivors the killing tests targeted are all dead.
 * #376 adds the two admin mapper ranges the #287 scope missed
 * (`describePaymentAttemptsError` 497-512 and
 * `describeDashboardError` 637-652, to EOF), both driven by the
 * existing `admin.test.tsx` mapper suites, and re-scopes the
 * settlement range 562-577 to the exact `describeSettlementError`
 * span 550-565: the old range tail-covered only 562-565 of the
 * function and spilled into `formatCents` plus the dashboard
 * constants (the #351 wrong-code failure mode). Admin ranges are
 * now 8 (7 mappers + the pre-existing 475-490
 * `useAdminPaymentAttempts` hook range); the mapper-range total
 * above is 36. Unmeasured locally — Stryker cannot run in this
 * sandbox — the next CI `frontend-mutation` run measures the
 * widened scope; the floor stays 91 until a measured run says
 * otherwise. No production-code change.
 * #395 extends the same discipline to the auth session hooks:
 * the six exported hooks in `src/api/auth.ts` (`useLoginHistory`,
 * `useSessions`, `useRevokeSession`, `useTrustedDevices`,
 * `useRevokeTrustedDevice`, `useRevokeOtherSessions`), one range
 * per hook from the current TypeScript AST (the derivation also
 * re-pins `describeStepUpError` 934-950 exactly, validating the
 * method). Key constants (`sessionKeys`, the step-up message) and
 * the plain async API functions are not in scope. Measured
 * separately first at break 0 (see docs/mutation-frontend.md,
 * "Twelfth measured run"): the first measure was 48.84% (21/43 —
 * all 19 survivors sat in the two onSuccess cache-update
 * callbacks plus the revoke-others invalidation, and the 3
 * no-coverage mutants were the `enabled = true` defaults, never
 * exercised because the only production caller passes an explicit
 * argument); 9 killing tests in the new
 * `src/api/authHooks.test.tsx` (renderHook, the notifications
 * pattern) closed the gap to 100.00% (43/43) on re-measurement,
 * so the combined arithmetic (1684+43)/(1843+43) = 91.57% still
 * clears the break-91 floor. No production-code change.
 * #396 re-scopes the two admin webhook mapper ranges the #376
 * follow-up left behind, re-derived from the current TypeScript
 * AST (`describeWebhookEventsError` 363-378 -> 372-387 and
 * `describeWebhookReplayError` 430-448 -> 439-457; the #389/#390
 * audit-row additions moved the functions down the file): the old
 * 358-373 range tail-covered only the first two lines of the
 * events mapper and spilled into `useAdminWebhookEvents`, and the
 * old 422-437 range covered none of the replay mapper at all —
 * it spanned the tail of `useReplayWebhookEvent` plus the
 * mapper's JSDoc (the #351 wrong-code failure mode). Both
 * mappers are driven by the existing `admin.test.tsx` mapper
 * suites, which cover every branch (403/401/400/default; 404/
 * 401-stale/401/403/default). Unmeasured locally — Stryker
 * cannot run in this sandbox — the next CI `frontend-mutation`
 * run measures the re-scoped ranges; the floor stays 91 until a
 * measured run says otherwise. No production-code change.
 * #401 re-derives the five remaining stale admin mapper ranges
 * from the current TypeScript AST (`describeAuditLogError`
 * 223-238 -> 225-240, `describeAdminUserError` 250-282 ->
 * 252-284, `describePaymentAttemptsError` 506-521 -> 508-523,
 * `describeSettlementError` 559-574 -> 561-576,
 * `describeDashboardError` 646-661 -> 648-663): a uniform +2
 * line shift, same line counts, caused by #397 adding two
 * `AUDIT_ACTIONS` entries (`REPORT_DISMISSED`,
 * `LISTING_HIDDEN`) above the mappers. The same +2 shift had
 * also re-staled #396's two webhook mapper ranges
 * (372-387 -> 374-389, 439-457 -> 441-459) and the
 * `useAdminPaymentAttempts` hook range (487-503 -> 489-505),
 * so this commit re-pins all eight admin ranges in one
 * mechanical correction rather than leaving three known-stale
 * ranges behind; the five mappers are the #401 item, the other
 * three are the same shift from the same cause, same files,
 * same discipline. All eight are driven by the existing
 * `admin.test.tsx` mapper suites, which cover every branch, so
 * no new tests were needed. The `check-mutation-gate.test.mjs`
 * pin list is updated to match. Unmeasured locally — Stryker
 * cannot run in this sandbox — the next CI `frontend-mutation`
 * run measures the re-scoped ranges; the floor stays 91 until
 * a measured run says otherwise. No production-code change.
 * #434 re-measures the api auth + reviews + offers mapper ranges
 * (the #287 weakest files) separately first at break 0, then
 * kills what survives: first measure 92.05% (162/176 — auth
 * 20/26, reviews 108/108, offers 34/42); 9 killing tests in
 * `src/api/auth.test.ts` and `src/api/offers.test.tsx` (mirror
 * matrices: branch copies with an empty message, response
 * without data, data without a message, message without a code,
 * no envelope at all) close all 14 gap mutants, and the
 * re-measure is 100.00% (176/176). The range derivation also
 * caught the #351 wrong-code failure mode in miniature:
 * `describeStepUpError` moved 934-950 -> 936-952 when #431
 * replaced the hardcoded strings with `t()` calls, so the
 * pinned range silently missed the fallback return on line 951
 * (the `return message || t('order.generic')` line carried no
 * mutants at all); the range is re-pinned here and the
 * `check-mutation-gate.test.mjs` pin list updated to match.
 * The other four ranges re-derive unchanged. The floor stays
 * 91: a chunk re-measurement does not earn a floor change (the
 * #388 precedent) — only a combined run re-derives it. No
 * production-code change.
 *
 * Reports land in `frontend/reports/mutation/` (JSON + clear text) and
 * the CI job uploads them as the `stryker-reports` artifact on every
 * run, so the measured score is retrievable, not just pass/fail.
 * `.stryker-tmp/` and `reports/` are gitignored build artifacts.
 *
 * @type {import('@stryker-mutator/api/core').PartialStrykerOptions}
 */
export default {
  packageManager: 'npm',
  testRunner: 'vitest',
  mutate: [
    'src/store/useCartStore.ts',
    'src/store/useAuthStore.ts',
    'src/store/useThemeStore.ts',
    'src/api/messages.ts:240-281',
    'src/api/categories.ts:142-168',
    'src/api/orders.ts:107-118',
    'src/api/orders.ts:306-324',
    'src/api/orders.ts:331-355',
    'src/api/orders.ts:438-459',
    'src/api/orders.ts:488-518',
    'src/api/orders.ts:554-578',
    'src/api/orders.ts:616-637',
    'src/api/orders.ts:665-683',
    'src/api/orders.ts:712-748',
    'src/api/items.ts:339-360',
    'src/api/items.ts:391-409',
    'src/api/items.ts:438-456',
    'src/api/items.ts:482-500',
    'src/api/admin.ts:225-240',
    'src/api/admin.ts:252-284',
    'src/api/admin.ts:374-389',
    'src/api/admin.ts:441-459',
    'src/api/admin.ts:489-505',
    'src/api/admin.ts:508-523',
    'src/api/admin.ts:561-576',
    'src/api/admin.ts:648-663',
    'src/api/reports.ts:52-73',
    'src/api/reports.ts:146-167',
    'src/api/offers.ts:183-207',
    'src/api/disputes.ts:158-179',
    'src/api/disputes.ts:182-212',
    'src/api/disputes.ts:297-321',
    'src/api/appeals.ts:95-122',
    'src/api/appeals.ts:195-219',
    'src/api/reviews.ts:113-134',
    'src/api/reviews.ts:151-175',
    'src/api/reviews.ts:178-196',
    'src/api/auth.ts:936-952',
    'src/api/auth.ts:794-800',
    'src/api/auth.ts:803-809',
    'src/api/auth.ts:817-828',
    'src/api/auth.ts:831-837',
    'src/api/auth.ts:845-856',
    'src/api/auth.ts:864-872',
    'src/api/client.ts:151-166',
    'src/api/favorites.ts:19-29',
    'src/api/favorites.ts:38-49',
    'src/api/follows.ts:30-40',
    'src/api/follows.ts:43-51',
    'src/api/follows.ts:54-62',
    'src/api/blocks.ts:28-38',
    'src/api/blocks.ts:41-50',
    'src/api/blocks.ts:53-62',
    'src/api/savedSearches.ts:47-57',
    'src/api/savedSearches.ts:66-83',
    'src/api/savedSearches.ts:90-98',
    'src/api/payouts.ts:48-59',
    'src/api/payouts.ts:62-69',
    'src/api/sellers.ts:30-39',
    'src/api/sellers.ts:42-53',
    'src/api/telemetry.ts:87-113',
    'src/api/telemetry.ts:121-149',
    'src/api/telemetry.ts:173-201',
    'src/api/webauthn.ts:17-24',
    'src/api/webauthn.ts:27-32',
    'src/api/webauthn.ts:35-41',
    'src/api/webauthn.ts:95-114',
    'src/api/webauthn.ts:121-131',
    'src/api/webauthn.ts:143-154',
    'src/api/webauthn.ts:162-175',
    'src/api/notifications.ts:207-216',
    'src/api/notifications.ts:223-232',
    'src/api/notifications.ts:256-313',
    'src/api/notifications.ts:320-329',
    'src/api/notifications.ts:339-353',
    'src/api/notifications.ts:361-372',
    'src/api/notifications.ts:379-388',
    'src/api/notifications.ts:395-406',
    'src/api/recentlyViewed.ts:22-32',
  ],
  reporters: ['clear-text', 'json'],
  thresholds: {
    high: 80,
    low: 60,
    break: 91,
  },
};
