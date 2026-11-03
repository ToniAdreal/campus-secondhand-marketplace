# Frontend mutation testing pilot (backlog #252, widened by #263, #280, #287 and #322)

Line coverage (Vitest, gated in the `frontend` CI job since #247) proves
code is *executed* by tests; it does not prove the tests would *notice*
if the behaviour changed. This pilot runs Stryker over the store modules
in the codebase and measures exactly that, mirroring the backend
PIT pilot (backlog #192, docs/mutation.md):

- `frontend/src/store/useCartStore.ts` — the zustand cart store plus its
  pure helpers (`deriveCartIdempotencyKey`, `cartTotalCents`,
  `revalidateCartItems`, `checkoutCart`).
- `frontend/src/store/useAuthStore.ts` (added by #263) — the zustand
  auth store: token-in-memory + refresh-queue semantics via the client
  interceptor, login/logout/hydrate with the TanStack cache clear.
- `frontend/src/store/useThemeStore.ts` (added by #280) — the last
  remaining zustand store: the preference initialised from storage
  at creation, `setPreference` persisting + applying in one step.
  With it, every zustand store in the codebase is now in scope.
- The 34 `describe*Error` mapper functions in the api modules
  (measured by #287, landed in the combined run — see "Fifth
  measured run" below; the ranges are in the gate) — as Stryker
  mutation ranges, one per function
  (see `frontend/stryker.config.mjs` for the exact ranges): the
  error-copy mappers only, never the hooks, queries or mutations
  that share their files. The ranges were derived from the
  TypeScript AST (function start/end lines); a mapper that moves
  lines needs its range re-derived, and the gate test pins the
  range list exactly, so a stale range fails the suite instead of
  silently mutating the wrong code.
- The 11 social-graph + saved-search hook functions (measured by
  #322, landed in the combined run — see "Sixth measured run"
  below; the ranges are in the gate): one range per exported hook
  (`useFavorites`, `useToggleFavorite`, `useMyFollows`,
  `useFollowSeller`, `useUnfollowSeller`, `useMyBlocks`,
  `useBlockUser`, `useUnblockUser`, `useSavedSearches`,
  `useCreateSavedSearch`, `useDeleteSavedSearch`), covering the
  queryFn malformed-payload fallback, the mutationFn and the
  onSuccess invalidation. Key constants, interfaces and the
  saved-searches pure helpers are out of scope — they are not hook
  paths.

The driving tests are the cart-store unit suite plus the page suites
that exercise the store through the cart page (`useCartStore.test.ts`,
`CartPage.test.tsx`, and related suites found by Vitest's `--related`
graph) — no backend, no browser — so a mutant is killed by an assertion
about behaviour, not by a page failing to render.

## How it runs

Stryker lives in `frontend/stryker.config.mjs` (Stryker 10.1.1, Vitest
runner). The config is **not part of the default local `npm test`
path**: run it explicitly:

```
npm run test:mutation --prefix frontend
```

CI runs it in the dedicated `frontend-mutation` job. The Vitest runner
always uses perTest coverage analysis and only runs tests related to
the mutated file, so one mutant costs the mutated file's suite, not the
whole ~1000-test suite.

Reports land in `frontend/reports/mutation/` (JSON; gitignored) and the
CI job uploads them as the `stryker-reports` artifact on every run.
`frontend/scripts/check-mutation-gate.mjs` is the wiring check (CI job
runs the script, config mutates existing files, `thresholds.break` is
numeric); its tests (`check-mutation-gate.test.mjs`, vitest like the
other frontend/scripts tests, run alone in the same job before the
mutation run) prove the gate decision fails below the
floor on synthetic scores — since #263 also via `mutationScoreFromReport` /
`reportPassesGate` over a fixture shaped like Stryker's `mutation.json`
(clean set passes, a deliberately weakened set fails). What the wiring
check does NOT prove is the score itself — that is Stryker's job:
`stryker run` exits non-zero when the measured score falls below
`thresholds.break`.

## Score and floor

The `thresholds.break` in the config started at **0 for the first
measurement run on purpose**: the floor is a ratchet set from a real
run's measured score, never guessed.

### First measured run (backlog #252)

Local run, 2026-10-28, head **9370922** (backlog #251), break 0,
duration 4 minutes 32 seconds.

| Scope | Mutation score | Test strength (covered) |
|---|---|---|
| `useCartStore.ts` (141 mutants) | 90.78% (128/141) | 95.52% (128/134) |

Mutant statuses: **KILLED 128, SURVIVED 6, NO_COVERAGE 7, TIMEOUT 0,
ERROR 0.** Mutation score counts killed over all mutants
(128/141 = 90.78%); test strength counts killed over covered mutants
only (128/134 = 95.52%). The 7 NO_COVERAGE mutants are the denominator
gap, not weakness in reached code: the reached code kills 95.52% of its
mutants.

### Floor decision

`thresholds.break` is now **90**: the measured mutation score is
90.78%, and the floor is that score rounded **down** to a whole
percent, so an identical rerun cannot fail on rounding. The floor is
never set above the measured score. It is set against the **current**
Stryker scope (the cart store only) at #252 time; #263 widened the
scope to the auth store and kept the floor at 90 — see "Floor decision
(widened scope)" below. Widening `mutate` further still requires
re-measuring first.

### Floor run (backlog #252)

The config with break 90 was re-executed locally on the same tree; the
run passed (score 90.78, at the floor by construction: 90 <= 90.78).
The next CI `frontend-mutation` run enforces the floor independently.

### Second measured run (backlog #263) — auth store

The auth store was measured separately first, with a temporary
auth-only config at break 0 (measure first, floor second — the same
discipline as #252; the temporary config was deleted after the run,
never committed). Local run, 2026-10-29, head **ddae22a** (backlog
#262) plus the #263 config/gate changes, which touch no auth-store
source:

| Scope | Mutation score | Test strength (covered) |
|---|---|---|
| `useAuthStore.ts` (19 mutants) | 94.74% (18/19) | 94.74% (18/19) |

Mutant statuses: **KILLED 18, SURVIVED 1, NO_COVERAGE 0, TIMEOUT 0,
ERROR 0.** Every mutant is covered, so the score and the test
strength coincide — the one survivor is a genuine test gap, not an
unreached path (see below).

### Floor decision (widened scope)

`thresholds.break` stays **90**: the cart-store floor is unchanged, not
lowered to fit the new scope. The widened config was then re-executed
locally at break 90 and passed: combined score **91.25% (146/160)** —
cart 90.78% (128/141) reproduced exactly, auth 94.74% (18/19) — in
4 minutes 46 seconds, with the same 7 survivors (6 cart + 1 auth) and
7 no-coverage mutants as the two separate runs. 90 <= 91.25, so the
unchanged floor still binds: a future widening that drops the combined
score below 90 fails the gate, and any floor move still requires a
fresh measured run first.

### Third measured run (backlog #280) — theme store

The theme store was measured separately first, with a temporary
theme-only config at break 0 (measure first, floor second — the same
discipline as #252 and #263; the temporary config was deleted after
the run, never committed). Local run, 2026-10-30, head **6fe72e5**
(backlog #279), break 0, duration 27 seconds.

| Scope | Mutation score | Test strength (covered) |
|---|---|---|
| `useThemeStore.ts` (5 mutants) | 100.00% (5/5) | 100.00% (5/5) |

Mutant statuses: **KILLED 5, SURVIVED 0, NO_COVERAGE 0, TIMEOUT 0,
ERROR 0.** The five mutants, with the test that killed each:

- L27 ArrowFunction (the `create` callback → `() => undefined`):
  killed by the first SettingsPage test, which renders the store
  through the page — a static mutant, evaluated at module import.
- L27 ObjectLiteral (initial state `{ preference }` → `{}`):
  killed by the ThemeControl Dark test, which reads the store state
  after picking a radio.
- L29 BlockStatement (`setPreference` body emptied): killed by the
  theme round-trip test (persist + repaint assertions).
- L32 CallExpression (`setStoredTheme` call dropped): killed by the
  ThemeControl Dark test (localStorage assertion).
- L33 ObjectLiteral (`set({ preference })` → `set({})`): killed by
  the ThemeControl Dark test (store-state assertion).

No killing tests were needed: the theme store scores above the floor
on its existing suite, so this item lands the measurement + the
widened scope, not a lowered floor.

### Floor decision (widened scope, second widening)

`thresholds.break` stays **90**: the floor is unchanged, not lowered
to fit the new scope. The widened config was then re-executed locally
at break 90 and passed: combined score **91.52% (151/165)** — cart
90.78% (128/141), auth 94.74% (18/19), theme 100.00% (5/5) — with the
same 7 survivors (6 cart + 1 auth) and 7 no-coverage mutants as
before, plus zero theme survivors. 90 <= 91.52, so the unchanged floor
still binds. Every zustand store is now in scope; a future widening
to api helpers still requires re-measuring first.

### Fourth measured run (backlog #287) — api error mappers

The mappers were measured separately first, with a temporary
mapper-ranges-only config at break 0 (measure first, floor second —
the same discipline as #252, #263 and #280; the temporary config
was deleted after the run, never committed). Local run, 2026-10-31,
head **e4c518e** (backlog #286) plus the #287 config/gate changes,
which touch no api source:

| Scope | Mutation score | Test strength (covered) |
|---|---|---|
| 34 `describe*Error` ranges (1256 mutants) | 75.72% (951/1256) | 86.61% (951/1098) |

Mutant statuses: **KILLED 951, SURVIVED 147, NO_COVERAGE 158,
TIMEOUT 0, ERROR 0.** Per-file scores: messages 96.08% (49/51),
reports 94.44% (68/72), disputes 87.30% (110/126), orders 86.67%
(325/375), appeals 84.88% (73/86), client 84.62% (11/13), offers
80.95% (34/42), admin 75.56% (136/180), auth 69.23% (18/26), reviews
63.89% (69/108), categories 36.36% (16/44), items 31.58% (42/133).
The gap is concentrated, not diffuse: items mappers 91 gap mutants
(reorder 34, markSold 29, renew 14, publish 14), categories admin
mapper 28, admin dashboard mapper 26 (all no-coverage — the
dashboard error copy has no covering test at all). By mutator:
OptionalChaining 100, ConditionalExpression 80, StringLiteral 57 —
the tests assert envelope/status far more than the exact per-status
copy, so the `error?.response?.status` chains and status branches
survive. The spec's "dense existing test matrices" claim does not
hold for the mappers: this is a real test gap, not equivalent
mutants, and it is NOT labelled equivalent here.

### Fifth measured run (backlog #287) — combined scope, landed

Killing tests for the mapper gap (dashboard, categories-admin,
reorder, markSold, renew, publish, orders first-chain and envelope
edges, landed as commits c78e695 through 1483e47) closed the
+177-kill gap the fourth run identified. The combined scope was
then re-measured at break 90 in an isolated clone (candidate tree
= head 1483e47 + only the five widening-proposal files, verified
identical to the persistent checkout's proposal versions; the
uncommitted #289 backend draft did not leak into the tree).
Local run, 2026-11-01, 27 minutes 35 seconds, 4 workers, dry run
1127 tests in 1m49s:

| Scope | Mutation score | Test strength (covered) |
|---|---|---|
| Stores + 34 `describe*Error` ranges (1421 mutants) | 93.53% (1329/1421) | 95.68% (1329/1389) |

Mutant statuses: **KILLED 1329, SURVIVED 60, NO_COVERAGE 32,
TIMEOUT 0, ERROR 0.** Per-file scores: categories 100.00%
(44/44), items 100.00% (133/133), orders 100.00% (375/375),
reports 100.00% (72/72), theme store 100.00% (5/5), messages
96.08% (49/51), appeals 97.67% (84/86), auth store 94.74%
(18/19), cart store 90.78% (128/141), admin 90.00% (162/180),
disputes 87.30% (110/126), client 84.62% (11/13), offers 80.95%
(34/42), reviews 79.63% (86/108), api auth 69.23% (18/26).
Survivors by mutator: OptionalChaining 36, ConditionalExpression
13, LogicalOperator 4, BlockStatement 3, ArrowFunction 2,
EqualityOperator 1, ObjectLiteral 1. No-coverage by mutator:
StringLiteral 13, ConditionalExpression 10, LogicalOperator 5,
OptionalChaining 2, BlockStatement 1, ObjectLiteral 1. The
weakest files (api auth, reviews, offers) stay honest follow-up
material; they do not block the landing, because the gate is the
combined score, not any single file.

### Floor decision (widened scope, third widening — LANDED)

`thresholds.break` stays **90**: the floor is unchanged, not lowered
to fit the new scope. The widened config lands in this round:
the combined score **93.53% (1329/1421)** clears the 90 floor
(90 <= 93.53), so the mapper ranges are now a landed scope, not a
proposal, and the proposal header is removed from
`frontend/stryker.config.mjs` together with the blocked wording in
the CI job comment. Backlog #287 is done; a future widening still
requires re-measuring first, per the #263 method.

### Sixth measured run (backlog #322) — social-graph + saved-search hooks

The hook ranges were measured separately first, with a temporary
hook-ranges-only config at break 0 (measure first, floor second —
the same discipline as #252, #263, #280 and #287; the temporary
config was deleted after the run, never committed; full report
copy: `goals/github-engineering-profile-building/hidden_files/stryker-322-measure-report.json`).
Local run, 2026-11-03, head **21cebab** (backlog #321) plus the
#322 config/gate/CI/doc changes, which touch no api source, 3
minutes 25 seconds, 4 workers:

| Scope | Mutation score | Test strength (covered) |
|---|---|---|
| 11 hook ranges (84 mutants) | 85.71% (72/84) | 86.75% (72/83) |

Mutant statuses: **KILLED 72, SURVIVED 11, NO_COVERAGE 1,
TIMEOUT 0, ERROR 0.** Per-file scores: savedSearches 96.15%
(25/26), follows 94.74% (18/19), favorites 92.86% (13/14), blocks
64.00% (16/25). The gap is concentrated, not diffuse: blocks
carries 9 of the 11 survivors (see below), the other three files
lose only the malformed-payload fallback each. None is labelled
equivalent here.

### Floor decision (widened scope, fourth widening — LANDED)

`thresholds.break` stays **90**: the floor is unchanged, not lowered
to fit the new scope. The new scope is disjoint from the landed
scope and no source file in either scope changed in this round,
so the combined score is the arithmetic sum of the two measured
runs: (1329 + 72) / (1421 + 84) = 1401/1505 = **93.09%**, which
clears the 90 floor (90 <= 93.09) — the hook ranges are now a
landed scope, and the CI `frontend-mutation` job measures the
combined score on every run. The 11 survivors + 1 no-coverage
mutant stay honest follow-up material (see the #322 subsection
under "Surviving mutants"); they do not block the landing,
because the gate is the combined score, not any single file.
Backlog #322 is done; a future widening still requires
re-measuring first, per the #263 method.

## Surviving mutants

The 6 SURVIVED mutants of the first run, with the test each one needs.
None is claimed killed here; none is labelled "equivalent". Killing
them is follow-up work for a later backlog item, which must re-measure
the score before touching the floor.

### `useAuthStore.hydrate` — 1 (#263)

- L81 ObjectLiteral (`set({ user: null })` → `set({})`): covered by
  exactly one test, survived. The no-session hydrate test starts from
  the logged-out state (user already null), so emptying the reset is
  indistinguishable from performing it — a stale user would survive
  the mutant unnoticed. Needs: log in first, then fail `hydrate`, and
  assert the user is null afterwards (the mutant would leave the stale
  user in place). Not labelled equivalent: the reset is load-bearing
  for the post-logout / failed-restore state.

### `useCartStore.removeItem` — 1

- L112 ConditionalExpression (`remaining.length === 0 ? emptyCart :
  items` → `false`): covered but survived. Tests remove an item but
  never remove the LAST item and assert the seller/batch clearing —
  the mutant keeps a stale `sellerId`/`batchId` on an empty cart and
  nothing notices. Needs: remove the last item, assert `sellerId`,
  `sellerUsername` and `batchId` are all null.

### `revalidateCartItems` — 3

- L198 ConditionalExpression (`fresh.status === 'AVAILABLE' && …` →
  `true && …`): covered but survived. No test holds a RESERVED/DRAFT
  row past its deadline and asserts it is KEPT — the mutant would drop
  it as expired. Needs: RESERVED row with a past `expiresAt` stays kept.
- L200 EqualityOperator (`<=` → `<`): covered by one test, survived.
  Tests use a clearly-past and a clearly-future deadline, never the
  boundary. Needs: `expiresAt` exactly equal to `now` drops as expired.
- L205 ConditionalExpression (`fresh.title !== item.title || …` →
  `false || …`): covered but survived. Tests change the price (or price
  + title) and assert `pricesChanged`, never a title-only change.
  Needs: title-only change sets `pricesChanged` true with the kept row
  carrying the new title.

### `checkoutCart` default error mapper — 1

- L230 ArrowFunction (default `describeError` → `() => undefined`):
  covered by success-path tests only, survived. Every failing-checkout
  test injects its own mapper, so the default is never exercised on a
  failure. Needs: a failing checkout with no injected mapper, asserting
  the fallback copy (`Something went wrong. Please try again.`).

### `useCartStore.clear` — 1

- L117 ArrowFunction (`clear` → `() => undefined`): ran the full
  related set and survived. No test calls `clear` and asserts the cart
  is empty afterwards. Needs: add two items, `clear`, assert empty
  items and null seller/batch.

### Social-graph / saved-search hooks — 11 survived + 1 no-coverage (#322)

None is labelled equivalent. Two test gaps explain all twelve:

- Malformed-payload fallback (4): the `Array.isArray(data) ? data
  : []` fallback survives in `useFavorites` (favorites L25),
  `useMyFollows` (follows L36) and `useMyBlocks` (blocks L34), and
  is no-coverage in `useSavedSearches` (savedSearches L53). No
  test feeds a non-array envelope to any of the four list hooks,
  so the fallback is never taken. Needs: one malformed-payload
  test per hook, asserting the hook resolves to `[]`.
- Block invalidation (8, all blocks): `useBlockUser` asserts only
  the `['blocks']` invalidation, so the second `['items']`
  invalidation's three mutants (blocks L47 ObjectLiteral,
  ArrayDeclaration, StringLiteral) survive; `useUnblockUser`
  asserts no invalidation at all, so its whole onSuccess body
  (L57–60 BlockStatement) plus both invalidations' mutants
  (L58 ObjectLiteral; L59 ObjectLiteral, ArrayDeclaration,
  StringLiteral) survive. Needs: assert the `['items']`
  invalidation on block, and both invalidations on unblock.

## No-coverage mutants

The 7 NO_COVERAGE mutants, all in paths no related test executes:

- L117 ObjectLiteral (`set({ ...emptyCart })` → `set({})`): the same
  untested `clear` path as the survivor above.
- L231–232, 6 mutants (ConditionalExpression ×2, LogicalOperator,
  OptionalChaining ×2, StringLiteral): the body of the default
  `describeError` fallback — uncovered for the same reason as the L230
  survivor: every failing-checkout test injects its own mapper.

Both groups share one root cause: the store's own fallbacks (`clear`,
the default error copy) are exercised only by pages, never asserted in
the unit suite. A follow-up that adds the six survivor tests above
should also cover these two paths, and the re-measured score — not a
guess — decides whether the floor moves.
