import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { useReducedMotion } from './useReducedMotion';

/**
 * Reduced-motion hook (backlog #427): the skeleton shimmer keys off
 * this hook, so its contract is pinned directly — the OS query is read
 * on mount, live changes are tracked, and environments without
 * matchMedia (jsdom by default) report "motion allowed" instead of
 * crashing.
 */

type Listener = () => void;
let listeners: Listener[] = [];
let matches = false;

function setReducedMotion(value: boolean) {
  matches = value;
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      // A live getter: the hook reads query.matches inside its change
      // listener, so the value must track the current `matches`, not
      // the value at matchMedia-call time.
      get matches() {
        return query === '(prefers-reduced-motion: reduce)' ? matches : false;
      },
      media: query,
      addEventListener: (_event: string, listener: Listener) => {
        listeners.push(listener);
      },
      removeEventListener: (_event: string, listener: Listener) => {
        listeners = listeners.filter((l) => l !== listener);
      },
    })),
  });
}

function Probe() {
  const reduced = useReducedMotion();
  return <p>{reduced ? 'reduced' : 'motion'}</p>;
}

describe('useReducedMotion (backlog #427)', () => {
  beforeEach(() => {
    listeners = [];
    setReducedMotion(false);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('reports motion allowed when the OS does not ask for reduced motion', () => {
    render(<Probe />);
    expect(screen.getByText('motion')).toBeTruthy();
  });

  it('reports reduced motion when the media query matches on mount', () => {
    setReducedMotion(true);
    render(<Probe />);
    expect(screen.getByText('reduced')).toBeTruthy();
  });

  it('tracks a live change to the media query', () => {
    render(<Probe />);
    expect(screen.getByText('motion')).toBeTruthy();

    act(() => {
      matches = true;
      listeners.forEach((l) => l());
    });
    expect(screen.getByText('reduced')).toBeTruthy();
  });

  it('stops listening after unmount', () => {
    const { unmount } = render(<Probe />);
    expect(listeners).toHaveLength(1);
    unmount();
    expect(listeners).toHaveLength(0);
  });
});
