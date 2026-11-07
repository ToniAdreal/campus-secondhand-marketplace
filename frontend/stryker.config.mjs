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
 * the 34 pure error-copy mappers (messages, categories, orders ×9,
 * items ×4, admin ×6, reports ×2, offers, disputes ×3, appeals ×2,
 * reviews ×3, auth step-up, client request) are in scope. The ranges
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
 * ranges below are a LANDED scope and the break-90 floor binds
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
    'src/api/categories.ts:143-169',
    'src/api/orders.ts:105-116',
    'src/api/orders.ts:266-284',
    'src/api/orders.ts:291-315',
    'src/api/orders.ts:398-419',
    'src/api/orders.ts:448-478',
    'src/api/orders.ts:514-538',
    'src/api/orders.ts:576-597',
    'src/api/orders.ts:625-643',
    'src/api/orders.ts:672-708',
    'src/api/items.ts:339-360',
    'src/api/items.ts:391-409',
    'src/api/items.ts:438-456',
    'src/api/items.ts:482-500',
    'src/api/admin.ts:215-230',
    'src/api/admin.ts:242-274',
    'src/api/admin.ts:359-374',
    'src/api/admin.ts:423-438',
    'src/api/admin.ts:476-491',
    'src/api/admin.ts:563-578',
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
    'src/api/auth.ts:934-950',
    'src/api/client.ts:71-80',
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
    'src/api/webauthn.ts:90-109',
    'src/api/webauthn.ts:116-126',
    'src/api/webauthn.ts:138-149',
    'src/api/webauthn.ts:157-170',
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
    break: 90,
  },
};
