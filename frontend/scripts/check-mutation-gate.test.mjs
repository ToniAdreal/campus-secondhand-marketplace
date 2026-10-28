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
  mutationPasses,
  parseStrykerConfig,
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

  it('real config: mutates the cart store and declares a numeric break floor', () => {
    const config = readRepo('frontend/stryker.config.mjs');
    const { mutate, breakThreshold } = parseStrykerConfig(config);
    expect(mutate).toEqual(['src/store/useCartStore.ts']);
    expect(typeof breakThreshold).toBe('number');
    expect(existsSync(join(repoRoot, 'frontend', 'src', 'store', 'useCartStore.ts'))).toBe(true);
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
