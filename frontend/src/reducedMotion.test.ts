import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Reduced-motion contract (backlog #361).
 *
 * The test reads the ACTUAL shipped stylesheet, frontend/src/index.css
 * (the theme.test.ts index.html pattern — never a duplicated constant),
 * so a future stylesheet rewrite cannot silently drop the
 * prefers-reduced-motion rule. The rule must name all three properties
 * the item pins: transitions, animations and smooth scrolling.
 */

function readIndexCss(): string {
  const candidates = [
    join(process.cwd(), 'src', 'index.css'),
    join(process.cwd(), 'frontend', 'src', 'index.css'),
  ];
  for (const candidate of candidates) {
    if (existsSync(candidate)) return readFileSync(candidate, 'utf-8');
  }
  throw new Error('index.css not found from the vitest working directory');
}

/** The `@media (prefers-reduced-motion: reduce)` block from the real CSS. */
function readReducedMotionBlock(): string {
  const css = readIndexCss();
  const start = css.indexOf('@media (prefers-reduced-motion: reduce)');
  expect(start, 'index.css must carry a prefers-reduced-motion media query').toBeGreaterThanOrEqual(0);
  const open = css.indexOf('{', start);
  let depth = 0;
  for (let i = open; i < css.length; i++) {
    if (css[i] === '{') depth++;
    if (css[i] === '}') {
      depth--;
      if (depth === 0) return css.slice(start, i + 1);
    }
  }
  throw new Error('unbalanced braces in the prefers-reduced-motion block');
}

describe('reduced motion (backlog #361)', () => {
  it('the shipped stylesheet carries a reduce-motion media query', () => {
    const css = readIndexCss();
    expect(css).toContain('@media (prefers-reduced-motion: reduce)');
  });

  it('the rule flattens transitions', () => {
    expect(readReducedMotionBlock()).toContain('transition-duration');
  });

  it('the rule flattens animations', () => {
    expect(readReducedMotionBlock()).toContain('animation-duration');
  });

  it('the rule pins scrolling to auto (no smooth scrolling)', () => {
    expect(readReducedMotionBlock()).toContain('scroll-behavior: auto');
  });

  it('the rule applies to every element, including pseudo-elements', () => {
    const block = readReducedMotionBlock();
    expect(block).toContain('*::before');
    expect(block).toContain('*::after');
  });
});
