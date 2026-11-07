// Tests for scripts/check-mutation-gate.mjs (backlog #252).
//
// Frontend convention (like check-bundle-size.test.mjs): this file uses
// vitest imports, so it runs inside the normal `npm test` suite —
// node:test files are NOT collected by vitest and fail as empty suites.
// The frontend-mutation CI job additionally runs this file alone
// (`npx vitest run scripts/check-mutation-gate.test.mjs`) before the
// mutation run, so the job-definition check runs in CI too.

import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import {
  checkMutationGateWiring,
  extractJob,
  floorFor,
  stripMutationRange,
  mutationPasses,
  mutationScoreFromReport,
  parseStrykerConfig,
  reportPassesGate,
} from './check-mutation-gate.mjs';

// Vitest rewrites import.meta.url to a /@fs virtual path, so URL-based
// root resolution breaks (it produced "repo.github/..." concatenations).
// Walk up from the process working directory instead: vitest and the CI
// step both run with cwd = frontend/, whose parent is the repo root.
function findRepoRoot() {
  let dir = process.cwd();
  for (;;) {
    if (existsSync(join(dir, '.github', 'workflows', 'ci.yml'))) return dir;
    const parent = dirname(dir);
    if (parent === dir) throw new Error('repo root not found above cwd');
    dir = parent;
  }
}

const repoRoot = findRepoRoot();
const readRepo = (rel) => readFileSync(join(repoRoot, rel), 'utf8');

describe('check-mutation-gate', () => {
  it('job definition: frontend-mutation job runs test:mutation, uploads the report, runs this check', () => {
    const ci = readRepo('.github/workflows/ci.yml');
    const job = extractJob(ci, 'frontend-mutation');
    expect(job.length).toBeGreaterThan(0);
    expect(job).toMatch(/test:mutation/);
    expect(job).toMatch(/stryker-reports/);
    expect(job).toMatch(/check-mutation-gate\.test\.mjs/);
    expect(job).toMatch(/check-mutation-gate\.mjs/);
  });

  it('real config: mutates the cart, auth and theme stores plus the #287 mapper ranges plus the #322 hook ranges plus the #327 payouts/sellers/telemetry/webauthn ranges plus the #347 notifications/recently-viewed ranges and declares a numeric break floor', () => {
    const config = readRepo('frontend/stryker.config.mjs');
    const { mutate, breakThreshold } = parseStrykerConfig(config);
    expect(mutate).toEqual([
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
    ]);
    expect(typeof breakThreshold).toBe('number');
    for (const file of mutate) {
      expect(existsSync(join(repoRoot, 'frontend', stripMutationRange(file)))).toBe(true);
    }
  });

  it('mutation ranges: the range suffix strips to the file path, plain entries pass through', () => {
    expect(stripMutationRange('src/api/messages.ts:240-281')).toBe('src/api/messages.ts');
    expect(stripMutationRange('src/api/orders.ts:105:1-116:1')).toBe('src/api/orders.ts');
    expect(stripMutationRange('src/store/useCartStore.ts')).toBe('src/store/useCartStore.ts');
    // A ranged entry whose base file is missing still fails wiring,
    // exactly like a plain missing file.
    const ci = readRepo('.github/workflows/ci.yml');
    const missingRanged = checkMutationGateWiring({
      ci,
      config: 'export default { mutate: ["src/api/nope.ts:1-10"], thresholds: { break: 90 } };',
      exists: () => false,
    });
    expect(missingRanged.some((f) => /missing file/.test(f))).toBe(true);
  });

  it('score-from-report: a clean-tree mutant set passes, a weakened set fails the floor', () => {
    // Fixture shaped exactly like Stryker's mutation.json (files map of
    // mutants with capitalized statuses). The clean set mirrors a
    // passing run (every mutant killed); the weakened set is the same
    // set with three mutants flipped to Survived/NoCoverage — the
    // deliberately weakened mutant set the #263 spec asks the gate to
    // reject, without re-running mutants to prove it.
    const mutant = (status) => ({ status });
    const cleanReport = {
      files: {
        'src/store/useAuthStore.ts': {
          mutants: Array.from({ length: 10 }, () => mutant('Killed')),
        },
      },
    };
    const weakenedReport = {
      files: {
        'src/store/useAuthStore.ts': {
          mutants: [
            ...Array.from({ length: 7 }, () => mutant('Killed')),
            mutant('Survived'),
            mutant('Survived'),
            mutant('NoCoverage'),
          ],
        },
      },
    };
    expect(mutationScoreFromReport(cleanReport)).toBe(100);
    expect(reportPassesGate(cleanReport, 90)).toBe(true);
    expect(mutationScoreFromReport(weakenedReport)).toBe(70);
    expect(reportPassesGate(weakenedReport, 90)).toBe(false);
    // Ignored mutants do not drag the score, and an empty report fails
    // loudly instead of passing vacuously.
    const ignoredReport = {
      files: { 'src/store/useAuthStore.ts': { mutants: [mutant('Killed'), mutant('Ignored')] } },
    };
    expect(mutationScoreFromReport(ignoredReport)).toBe(100);
    expect(() => mutationScoreFromReport({ files: {} })).toThrow(/no countable mutants/);
    expect(() => mutationScoreFromReport({})).toThrow(/files map/);
  });

  it('gate decision: score at the floor passes, a tenth below it fails', () => {
    expect(mutationPasses(72, 72)).toBe(true);
    expect(mutationPasses(72.0, 72)).toBe(true);
    expect(mutationPasses(71.9, 72)).toBe(false);
    expect(mutationPasses(0, 0)).toBe(true);
  });

  it('ratchet rule: floor rounds the measured score DOWN, never up', () => {
    expect(floorFor(72.9)).toBe(72);
    expect(floorFor(72.0)).toBe(72);
    expect(floorFor(0.4)).toBe(0);
    expect(() => floorFor(Number.NaN)).toThrow(/finite score/);
  });

  it('wiring check fails closed: missing job, empty mutate list, missing file', () => {
    // A config whose break (100) no real score could meet is still
    // *wired* — wiring and score are separate concerns — so this
    // asserts the other direction: a job that stops running the
    // mutation script, or a config that mutates nothing, fails the
    // wiring check.
    const ci = readRepo('.github/workflows/ci.yml');
    const config = readRepo('frontend/stryker.config.mjs');
    const noJob = checkMutationGateWiring({
      ci: 'name: CI\njobs:\n  frontend:\n    runs-on: ubuntu-latest\n',
      config,
      exists: () => true,
    });
    expect(noJob.some((f) => /no frontend-mutation job/.test(f))).toBe(true);
    const noMutate = checkMutationGateWiring({
      ci,
      config: 'export default { thresholds: { break: 100 } };',
      exists: () => true,
    });
    expect(noMutate.some((f) => /mutates no files/.test(f))).toBe(true);
    const missingFile = checkMutationGateWiring({
      ci,
      config: 'export default { mutate: ["src/nope.ts"], thresholds: { break: 100 } };',
      exists: () => false,
    });
    expect(missingFile.some((f) => /missing file/.test(f))).toBe(true);
    // And the real pair passes wiring (the score-vs-floor proof is the
    // Stryker run itself, recorded in docs/mutation-frontend.md).
    expect(checkMutationGateWiring({
      ci,
      config,
      exists: (file) => existsSync(join(repoRoot, 'frontend', file)),
    })).toEqual([]);
  });

  it('score-vs-floor: a synthetic report below break fails the gate decision', () => {
    // Mirrors the #247 scratch-copy proof (thresholds raised to 100 made
    // the run go red) without re-running mutants: the decision function
    // Stryker's break implements is exactly mutationPasses.
    expect(mutationPasses(50, 100)).toBe(false);
    expect(mutationPasses(100, 100)).toBe(true);
  });
});
