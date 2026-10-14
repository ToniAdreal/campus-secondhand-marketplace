// Bundle-size budget check (backlog #165).
//
// Why: route-level code splitting landed in #97, but nothing measured the
// build output — a single stray import could quietly pull the whole app
// back into the entry chunk and CI would stay green. This script is the
// ratchet: it reads the real Vite output, prints a per-chunk table on
// every run, and fails when the entry chunk or the total JS exceeds the
// budgets below.
//
// Budgets (raw bytes, not gzip — raw is what Vite writes and what a
// reader can reproduce with `ls -l dist/assets`):
//   measured 2026-10-14 on commit 3535cb1 (the tree just before this
//   script landed; this script, its test, and the CI step do not feed
//   the bundle): entry chunk index-*.js = 300,953 bytes, total JS =
//   414,003 bytes across 32 chunks.
//   ENTRY_MAX_BYTES = 316,000  (measured + ~5%, rounded up)
//   TOTAL_MAX_BYTES = 435,000  (measured + ~5%, rounded up)
//
// Bump procedure (deliberate, never automatic): if a change legitimately
// needs more room, edit the constant in the same commit, and say in the
// commit message what grew and why. Shrinking a budget after a real
// size win is equally welcome — that is what makes it a ratchet.
//
// No dependencies: plain node, run via `npm run size` after
// `npm run build`, and as a CI step in the frontend job.

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const ENTRY_MAX_BYTES = 316_000;
export const TOTAL_MAX_BYTES = 435_000;

/**
 * The entry chunk is whatever dist/index.html loads as its module
 * script — never a name guess, so a future manualChunks split that
 * renames the entry is followed automatically.
 * Returns the chunk's file name (basename), or null when the HTML has
 * no module script (a broken build must fail the check, not pass it).
 */
export function findEntryChunkName(indexHtml) {
  const match = indexHtml.match(/<script[^>]*type="module"[^>]*src="([^"]+\.js)"/);
  return match ? basename(match[1]) : null;
}

/**
 * Pure size check. `files` is a listing of { name, size } for the JS
 * files in dist/assets; `entryName` comes from findEntryChunkName.
 * Returns { ok, entry, total, violations } where entry is the entry
 * chunk's { name, size } (or null) and violations is a list of
 * human-readable strings, one per failed budget.
 */
export function evaluateBundle(files, entryName, budgets = { entryMax: ENTRY_MAX_BYTES, totalMax: TOTAL_MAX_BYTES }) {
  const violations = [];
  const total = files.reduce((sum, f) => sum + f.size, 0);
  const entry = entryName ? files.find((f) => f.name === entryName) ?? null : null;

  if (!entry) {
    violations.push(
      entryName
        ? `entry chunk ${entryName} (from dist/index.html) not found in dist/assets`
        : 'no module entry script found in dist/index.html',
    );
  } else if (entry.size > budgets.entryMax) {
    violations.push(`entry chunk ${entry.name} is ${entry.size} bytes, over the ${budgets.entryMax}-byte budget`);
  }
  if (total > budgets.totalMax) {
    violations.push(`total JS is ${total} bytes, over the ${budgets.totalMax}-byte budget`);
  }
  return { ok: violations.length === 0, entry, total, violations };
}

/** Per-chunk table, largest first — growth stays visible before it fails. */
export function formatChunkTable(files, entryName) {
  const sorted = [...files].sort((a, b) => b.size - a.size);
  const rows = sorted.map(
    (f) => `${String(f.size).padStart(10)}  ${f.name}${f.name === entryName ? '  (entry)' : ''}`,
  );
  const total = files.reduce((sum, f) => sum + f.size, 0);
  rows.push(`${String(total).padStart(10)}  TOTAL (${files.length} chunks)`);
  return rows.join('\n');
}

function main() {
  const frontendDir = join(dirname(fileURLToPath(import.meta.url)), '..');
  const assetsDir = join(frontendDir, 'dist', 'assets');
  let files;
  let indexHtml;
  try {
    files = readdirSync(assetsDir)
      .filter((name) => name.endsWith('.js'))
      .map((name) => ({ name, size: statSync(join(assetsDir, name)).size }));
    indexHtml = readFileSync(join(frontendDir, 'dist', 'index.html'), 'utf8');
  } catch {
    console.error('No build output found — run `npm run build` first.');
    process.exit(1);
  }

  const entryName = findEntryChunkName(indexHtml);
  const result = evaluateBundle(files, entryName);
  console.log(formatChunkTable(files, entryName));
  console.log(
    `Budgets: entry <= ${ENTRY_MAX_BYTES} bytes, total <= ${TOTAL_MAX_BYTES} bytes.`,
  );
  if (!result.ok) {
    for (const violation of result.violations) {
      console.error(`BUNDLE BUDGET EXCEEDED: ${violation}`);
    }
    process.exit(1);
  }
  console.log('Bundle size within budget.');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
