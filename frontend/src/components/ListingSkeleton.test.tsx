import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ListingDetailSkeleton, ListingGridSkeleton } from './ListingSkeleton';

/**
 * Skeleton loading states (backlog #427).
 *
 * The grid skeleton mirrors the browse card's dimensions so the swap
 * to real rows causes no layout shift, and the detail skeleton mirrors
 * the detail article — both carry an accessible "Loading" label with
 * aria-busy, and the shimmer sweep is present only when the OS does
 * not ask for reduced motion. Page-level behaviour (skeletons while
 * the query is pending, real content replacing them) is pinned in
 * HomePage.test.tsx / ItemDetailPage.test.tsx; this file pins the
 * components themselves.
 */

function setReducedMotion(value: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: query === '(prefers-reduced-motion: reduce)' ? value : false,
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    })),
  });
}

describe('ListingGridSkeleton (backlog #427)', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('announces itself as a busy loading region, not bare text', () => {
    render(
      <MemoryRouter>
        <ListingGridSkeleton />
      </MemoryRouter>,
    );

    const status = screen.getByRole('status', { name: /loading listings/i });
    expect(status.getAttribute('aria-busy')).toBe('true');
    expect(screen.queryByText(/loading listings/i)).toBeTruthy();
    // The old bare paragraph is gone — the region is a div, and the
    // only "Loading listings…" copy is the sr-only label inside it.
    expect(status.tagName).toBe('DIV');
    expect(status.querySelector('p')).toBeNull();
  });

  it('renders six skeleton cards by default (two full rows at lg)', () => {
    render(
      <MemoryRouter>
        <ListingGridSkeleton />
      </MemoryRouter>,
    );

    expect(screen.getAllByTestId('listing-card-skeleton')).toHaveLength(6);
  });

  it('honours an explicit count', () => {
    render(
      <MemoryRouter>
        <ListingGridSkeleton count={3} />
      </MemoryRouter>,
    );

    expect(screen.getAllByTestId('listing-card-skeleton')).toHaveLength(3);
  });

  it('each card mirrors the browse card dimensions (photo, title, price, pill)', () => {
    const { container } = render(
      <MemoryRouter>
        <ListingGridSkeleton count={1} />
      </MemoryRouter>,
    );

    const card = screen.getByTestId('listing-card-skeleton');
    expect(card.className).toContain('rounded-[14px]');
    expect(card.className).toContain('p-4');
    // Photo block, title bar, price bar, status pill + favourite dot —
    // the same heights the real card occupies, so rows do not jump.
    const bars = container.querySelectorAll('[aria-hidden="true"]');
    const classes = Array.from(bars).map((b) => b.className);
    expect(classes.some((c) => c.includes('h-40'))).toBe(true);
    expect(classes.some((c) => c.includes('h-5'))).toBe(true);
    expect(classes.some((c) => c.includes('h-7'))).toBe(true);
    expect(classes.some((c) => c.includes('rounded-full'))).toBe(true);
  });

  it('shimmers by default and goes static under reduced motion', () => {
    const { container, unmount } = render(
      <MemoryRouter>
        <ListingGridSkeleton count={1} />
      </MemoryRouter>,
    );
    expect(container.querySelector('.skeleton-shimmer')).not.toBeNull();

    unmount();
    cleanup();
    setReducedMotion(true);
    const reduced = render(
      <MemoryRouter>
        <ListingGridSkeleton count={1} />
      </MemoryRouter>,
    );
    expect(reduced.container.querySelector('.skeleton-shimmer')).toBeNull();
    // Static bars are still visible placeholders, not an empty region.
    expect(reduced.container.querySelector('.bg-neutral-200')).not.toBeNull();
  });
});

describe('ListingDetailSkeleton (backlog #427)', () => {
  beforeEach(() => {
    setReducedMotion(false);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('announces itself as a busy loading region with the article shape', () => {
    render(
      <MemoryRouter>
        <ListingDetailSkeleton />
      </MemoryRouter>,
    );

    const status = screen.getByRole('status', { name: /loading listing/i });
    expect(status.getAttribute('aria-busy')).toBe('true');
    expect(status.getAttribute('data-testid')).toBe('listing-detail-skeleton');
    // Same shell as the real article: no layout shift on swap.
    expect(status.className).toContain('rounded-lg');
    expect(status.className).toContain('p-6');
    expect(screen.queryByText('Loading…')).toBeNull();
  });

  it('mirrors the detail layout (title, gallery, price, description, actions)', () => {
    const { container } = render(
      <MemoryRouter>
        <ListingDetailSkeleton />
      </MemoryRouter>,
    );

    const classes = Array.from(container.querySelectorAll('[aria-hidden="true"]')).map(
      (b) => b.className,
    );
    expect(classes.some((c) => c.includes('h-8'))).toBe(true); // title
    expect(classes.some((c) => c.includes('h-48'))).toBe(true); // gallery
    expect(classes.filter((c) => c.includes('h-16'))).toHaveLength(3); // thumbnails
    expect(classes.some((c) => c.includes('h-7'))).toBe(true); // price
    expect(classes.some((c) => c.includes('h-10'))).toBe(true); // action button
  });

  it('goes static under reduced motion', () => {
    setReducedMotion(true);
    const { container } = render(
      <MemoryRouter>
        <ListingDetailSkeleton />
      </MemoryRouter>,
    );

    expect(container.querySelector('.skeleton-shimmer')).toBeNull();
    expect(container.querySelector('.bg-neutral-200')).not.toBeNull();
  });
});
