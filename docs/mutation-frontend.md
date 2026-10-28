# Frontend mutation testing pilot (backlog #252)

Line coverage (Vitest, gated in the `frontend` CI job since #247) proves
code is *executed* by tests; it does not prove the tests would *notice*
if the behaviour changed. This pilot runs Stryker over the purest store
module in the codebase and measures exactly that, mirroring the backend
PIT pilot (backlog #192, docs/mutation.md):

- `frontend/src/store/useCartStore.ts` — the zustand cart store plus its
  pure helpers (`deriveCartIdempotencyKey`, `cartTotalCents`,
  `revalidateCartItems`, `checkoutCart`).

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
the mutated file, so one mutant costs the cart-store suite, not the
whole ~980-test suite.

Reports land in `frontend/reports/mutation/` (JSON; gitignored) and the
CI job uploads them as the `stryker-reports` artifact on every run.
`frontend/scripts/check-mutation-gate.mjs` is the wiring check (CI job
runs the script, config mutates existing files, `thresholds.break` is
numeric); its tests (`check-mutation-gate.test.mjs`, vitest like the
other frontend/scripts tests, run alone in the same job before the
mutation run) prove the gate decision fails below the
floor on synthetic scores. What the wiring check does NOT prove is the
score itself — that is Stryker's job: `stryker run` exits non-zero when
the measured score falls below `thresholds.break`.

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
Stryker scope (the cart store only) — widening `mutate` to more api
helpers / stores is future work, not done here, and will require
re-measuring.

### Floor run (backlog #252)

The config with break 90 was re-executed locally on the same tree; the
run passed (score 90.78, at the floor by construction: 90 <= 90.78).
The next CI `frontend-mutation` run enforces the floor independently.

## Surviving mutants

The 6 SURVIVED mutants of the first run, with the test each one needs.
None is claimed killed here; none is labelled "equivalent". Killing
them is follow-up work for a later backlog item, which must re-measure
the score before touching the floor.

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
