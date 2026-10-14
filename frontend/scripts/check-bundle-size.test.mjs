import { describe, expect, it } from 'vitest';

import {
  ENTRY_MAX_BYTES,
  TOTAL_MAX_BYTES,
  evaluateBundle,
  findEntryChunkName,
  formatChunkTable,
} from './check-bundle-size.mjs';

// Fixture listings only — the script's pure functions never touch the
// filesystem, so the budgets are pinned against synthetic chunk lists
// sized around the real constants.
const budgets = { entryMax: 1_000, totalMax: 2_500 };
const under = [
  { name: 'index-abc.js', size: 900 },
  { name: 'HomePage-def.js', size: 800 },
  { name: 'orders-ghi.js', size: 500 },
];

describe('findEntryChunkName', () => {
  it('reads the module script Vite writes into dist/index.html', () => {
    const html =
      '<script type="module" crossorigin src="/assets/index-BldqOlc_.js"></script>';
    expect(findEntryChunkName(html)).toBe('index-BldqOlc_.js');
  });

  it('returns null when the HTML has no module script', () => {
    expect(findEntryChunkName('<script src="/legacy.js"></script>')).toBeNull();
  });
});

describe('evaluateBundle', () => {
  it('passes when entry and total are both under budget', () => {
    const result = evaluateBundle(under, 'index-abc.js', budgets);
    expect(result.ok).toBe(true);
    expect(result.violations).toEqual([]);
    expect(result.entry).toEqual({ name: 'index-abc.js', size: 900 });
    expect(result.total).toBe(2_200);
  });

  it('fails when the entry chunk alone exceeds its budget', () => {
    const files = [
      { name: 'index-abc.js', size: 1_001 },
      { name: 'HomePage-def.js', size: 100 },
    ];
    const result = evaluateBundle(files, 'index-abc.js', budgets);
    expect(result.ok).toBe(false);
    expect(result.violations).toHaveLength(1);
    expect(result.violations[0]).toContain('entry chunk index-abc.js');
  });

  it('fails when the total exceeds its budget even with a lean entry', () => {
    const files = [
      { name: 'index-abc.js', size: 100 },
      { name: 'HomePage-def.js', size: 1_300 },
      { name: 'orders-ghi.js', size: 1_200 },
    ];
    const result = evaluateBundle(files, 'index-abc.js', budgets);
    expect(result.ok).toBe(false);
    expect(result.violations).toHaveLength(1);
    expect(result.violations[0]).toContain('total JS');
  });

  it('fails when the entry chunk named by the HTML is missing', () => {
    const result = evaluateBundle(under, 'index-missing.js', budgets);
    expect(result.ok).toBe(false);
    expect(result.entry).toBeNull();
    expect(result.violations[0]).toContain('not found');
  });

  it('ships budgets that leave headroom over a real listing shape', () => {
    // The constants are the contract CI enforces; this pins that they
    // are positive and that the entry budget is the tighter of the two.
    expect(ENTRY_MAX_BYTES).toBeGreaterThan(0);
    expect(TOTAL_MAX_BYTES).toBeGreaterThan(ENTRY_MAX_BYTES);
  });
});

describe('formatChunkTable', () => {
  it('lists chunks largest-first, marks the entry, and totals', () => {
    const table = formatChunkTable(under, 'index-abc.js');
    const lines = table.split('\n');
    expect(lines[0]).toContain('index-abc.js');
    expect(lines[0]).toContain('(entry)');
    expect(lines[lines.length - 1]).toContain('TOTAL (3 chunks)');
    expect(lines[lines.length - 1]).toContain('2200');
  });
});
