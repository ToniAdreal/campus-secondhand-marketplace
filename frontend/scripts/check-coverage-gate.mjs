// CI-config check for the frontend coverage gate (backlog #247).
//
// What it proves (cheaply, without running the suite):
//   1. the CI `frontend` job actually runs the coverage script — a
//      threshold that no job executes is not a gate;
//   2. vite.config.ts declares numeric thresholds for all four
//      metrics, so the gate cannot silently degrade to "no floor".
//
// What it does NOT prove: that the tree meets the thresholds. That is
// vitest's job — `npm run test:coverage` fails the run when any
// metric falls below its threshold (verified by the #247 round's
// scratch-copy test: thresholds raised to 100 made the run go red).
// Run: `node scripts/check-coverage-gate.mjs` (from frontend/) or in CI
// after the coverage step.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');
const failures = [];

const ci = readFileSync(join(root, '.github', 'workflows', 'ci.yml'), 'utf8');
// Slice out the frontend job: lines from "  frontend:" up to (not
// including) the next top-level job header ("  <name>:").
const ciLines = ci.split('\n');
let inFrontend = false;
const jobLines = [];
for (const line of ciLines) {
  if (line === '  frontend:') {
    inFrontend = true;
    continue;
  }
  if (inFrontend && /^  [a-zA-Z0-9_-]+:/.test(line)) break;
  if (inFrontend) jobLines.push(line);
}
const frontendJob = jobLines.join('\n');
if (!frontendJob.includes('test:coverage')) {
  failures.push('CI frontend job does not run test:coverage');
}

const config = readFileSync(join(here, '..', 'vite.config.ts'), 'utf8');
const thresholds = {};
for (const metric of ['lines', 'functions', 'branches', 'statements']) {
  const m = config.match(new RegExp(`${metric}:\\s*(\\d+)`));
  if (!m) {
    failures.push(`vite.config.ts has no numeric threshold for ${metric}`);
  } else {
    thresholds[metric] = Number(m[1]);
  }
}

if (failures.length > 0) {
  for (const f of failures) console.error(`coverage-gate check FAILED: ${f}`);
  process.exit(1);
}
console.log(
  `coverage-gate check OK: CI frontend job runs test:coverage; thresholds ${JSON.stringify(thresholds)}`,
);
