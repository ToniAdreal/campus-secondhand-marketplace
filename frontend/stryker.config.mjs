/**
 * Frontend mutation testing (backlog #252, widened by #263), mirroring
 * the backend PIT pilot (backlog #192, docs/mutation.md) and the #165
 * ratchet discipline.
 *
 * Scope is deliberately narrow on purpose: the two store modules below
 * are zustand stores with focused unit suites, so a mutant is killed by
 * an assertion about behaviour, not by a page failing to render. The
 * cart store (`src/store/useCartStore.ts`) is the purest store module
 * plus pure helpers (`deriveCartIdempotencyKey`, `cartTotalCents`,
 * `revalidateCartItems`, `checkoutCart`); the auth store
 * (`src/store/useAuthStore.ts`, added by #263) is the auth-direction
 * core — token-in-memory + refresh-queue semantics via the client
 * interceptor, login/logout/hydrate with the TanStack cache clear.
 * Widening the scope to more api helpers / stores is future work and
 * requires re-measuring the floor first.
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
 * new scope.
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
  mutate: ['src/store/useCartStore.ts', 'src/store/useAuthStore.ts'],
  reporters: ['clear-text', 'json'],
  thresholds: {
    high: 80,
    low: 60,
    break: 90,
  },
};
