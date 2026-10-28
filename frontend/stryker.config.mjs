/**
 * Frontend mutation testing (backlog #252), mirroring the backend PIT
 * pilot (backlog #192, docs/mutation.md) and the #165 ratchet discipline.
 *
 * Scope is deliberately narrow on purpose: the cart store
 * (`src/store/useCartStore.ts`) is the purest store module — a zustand
 * store plus pure helpers (`deriveCartIdempotencyKey`, `cartTotalCents`,
 * `revalidateCartItems`, `checkoutCart`) with a focused unit suite, so a
 * mutant is killed by an assertion about behaviour, not by a page
 * failing to render. Widening the scope to more api helpers / stores is
 * future work and requires re-measuring the floor first.
 *
 * How it runs: Stryker with the Vitest runner. The runner always uses
 * perTest coverage analysis and, by default, only runs tests related to
 * the mutated file, so one mutant costs the cart-store suite, not the
 * whole 980-test suite. The config is NOT part of the default local
 * `npm test` path: run `npm run test:mutation` explicitly, or let the
 * dedicated `frontend-mutation` CI job run it.
 *
 * Score and floor: `thresholds.break` is the gate — Stryker fails the
 * run when the mutation score falls below it. The floor is set from a
 * real measured run, rounded DOWN to a whole percent, never above the
 * measured score (see docs/mutation-frontend.md for the run, the score
 * and the surviving mutants). The first measurement run used break 0
 * on purpose (2026-10-28, score 90.78 over 141 mutants); the floor
 * below is that score floored to 90, and the floor run re-executes
 * Stryker with break 90 to prove the gate passes at the floor.
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
  mutate: ['src/store/useCartStore.ts'],
  reporters: ['clear-text', 'json'],
  thresholds: {
    high: 80,
    low: 60,
    break: 90,
  },
};
