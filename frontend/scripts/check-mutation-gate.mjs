// CI-config check for the frontend mutation gate (backlog #252).
//
// What it proves (cheaply, without running mutants):
//   1. the CI `frontend-mutation` job actually runs the mutation script
//      (`npm run test:mutation`, i.e. `stryker run`) — a threshold that
//      no job executes is not a gate;
//   2. the job uploads the Stryker report as an artifact, so the
//      measured score is retrievable, not just pass/fail;
//   3. stryker.config.mjs scopes `mutate` to files that exist and
//      declares a numeric `thresholds.break` — the gate cannot silently
//      degrade to "no floor";
//   4. the gate decision itself: `mutationPasses(score, break)` is true
//      exactly when score >= break, and `floorFor(score)` is the #165
//      ratchet rule (measured score rounded DOWN to a whole percent).
//
// What it does NOT prove: that the tree meets the floor. That is
// Stryker's job — `stryker run` exits non-zero when the measured score
// falls below `thresholds.break` (verified by the #252 round's
// measurement run: the break-0 run's score sets the floor, and the
// floor run re-executes Stryker with break set). The test file proves
// the fail-below logic on synthetic scores so the "fails below it"
// claim does not rest on re-running mutants.
// Run: `node scripts/check-mutation-gate.mjs` (from frontend/) or in CI
// after the mutation step. Pure functions are exported for
// scripts/check-mutation-gate.test.mjs (vitest, like the other
// frontend/scripts tests); the CLI at the bottom only runs when the
// file is executed directly.

import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/** The #165 ratchet rule: floor the measured score, never round up. */
export function floorFor(score) {
  if (typeof score !== 'number' || !Number.isFinite(score)) {
    throw new Error(`floorFor needs a finite score, got ${score}`);
  }
  return Math.floor(score);
}

/** Stryker's gate decision: the run passes exactly when score >= break. */
export function mutationPasses(score, breakThreshold) {
  if (typeof score !== 'number' || !Number.isFinite(score)) {
    throw new Error(`mutationPasses needs a finite score, got ${score}`);
  }
  if (typeof breakThreshold !== 'number' || !Number.isFinite(breakThreshold)) {
    throw new Error(`mutationPasses needs a finite break, got ${breakThreshold}`);
  }
  return score >= breakThreshold;
}

/** Slice one job out of a GitHub Actions workflow file's text. */
export function extractJob(ci, jobName) {
  const lines = ci.split('\n');
  let inJob = false;
  const jobLines = [];
  for (const line of lines) {
    if (line === `  ${jobName}:`) {
      inJob = true;
      continue;
    }
    if (inJob && /^  [a-zA-Z0-9_-]+:/.test(line)) break;
    if (inJob) jobLines.push(line);
  }
  return jobLines.join('\n');
}

/** Parse the `mutate` list and `thresholds.break` out of stryker.config.mjs text. */
export function parseStrykerConfig(configText) {
  const mutateMatch = configText.match(/mutate:\s*\[([\s\S]*?)\]/);
  const mutate = mutateMatch
    ? [...mutateMatch[1].matchAll(/['"]([^'"]+)['"]/g)].map((m) => m[1])
    : [];
  const breakMatch = configText.match(/break:\s*(\d+(?:\.\d+)?)/);
  return {
    mutate,
    breakThreshold: breakMatch ? Number(breakMatch[1]) : null,
  };
}

/** Wiring failures for a { ci, config } pair; [] means the gate is wired. */
export function checkMutationGateWiring({ ci, config, exists }) {
  const failures = [];
  const job = extractJob(ci, 'frontend-mutation');
  if (!job) {
    failures.push('CI has no frontend-mutation job');
    return failures;
  }
  if (!job.includes('test:mutation') && !job.includes('stryker run')) {
    failures.push('CI frontend-mutation job does not run test:mutation');
  }
  if (!job.includes('stryker-reports') && !job.includes('reports/mutation')) {
    failures.push('CI frontend-mutation job does not upload the Stryker report');
  }
  if (!job.includes('check-mutation-gate.test.mjs')) {
    failures.push('CI frontend-mutation job does not run check-mutation-gate.test.mjs');
  }
  const { mutate, breakThreshold } = parseStrykerConfig(config);
  if (mutate.length === 0) {
    failures.push('stryker.config.mjs mutates no files');
  }
  for (const file of mutate) {
    if (!exists(file)) failures.push(`stryker.config.mjs mutates missing file ${file}`);
  }
  if (breakThreshold === null) {
    failures.push('stryker.config.mjs has no numeric thresholds.break');
  }
  return failures;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const here = dirname(fileURLToPath(import.meta.url));
  const root = join(here, '..', '..');
  const ci = readFileSync(join(root, '.github', 'workflows', 'ci.yml'), 'utf8');
  const config = readFileSync(join(here, '..', 'stryker.config.mjs'), 'utf8');
  const { mutate, breakThreshold } = parseStrykerConfig(config);
  const failures = checkMutationGateWiring({
    ci,
    config,
    exists: (file) => existsSync(join(here, '..', file)),
  });
  if (failures.length > 0) {
    for (const f of failures) console.error(`mutation-gate check FAILED: ${f}`);
    process.exit(1);
  }
  console.log(
    `mutation-gate check OK: CI frontend-mutation job runs test:mutation; `
    + `mutate ${JSON.stringify(mutate)}; thresholds.break ${breakThreshold}`,
  );
}
