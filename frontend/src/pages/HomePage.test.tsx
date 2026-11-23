import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import HomePage from './HomePage';
import type { CursorPage, Page, Item } from '../api/items';
import type { AuthUser } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';

const calls: Array<{
  page: number;
  q: string;
  categoryId: number | null;
  filters?: { minPriceCents?: number | null; maxPriceCents?: number | null; sort?: string | null };
}> = [];

/** Content the mocked useItems serves; empty unless a test seeds listings. */
let mockContent: Item[] = [];

/** When true, both items mocks answer "pending" with no data (backlog #427). */
let mockItemsLoading = false;

/** Suggestions the mocked useItemSuggestions serves; empty unless a test seeds titles. */
let mockSuggestions: Array<{ id: number; title: string }> = [];

/** Page-count override for the pagination tests; null derives it from content. */
let mockTotalPages: number | null = null;

/** Cursor-mode invocations; the enabled flag is what pins "price sort stays on offset". */
const cursorCalls: Array<{
  q: string;
  categoryId: number | null;
  filters?: { minPriceCents?: number | null; maxPriceCents?: number | null; sort?: string | null };
  enabled: boolean;
}> = [];

/**
 * Cursor pages the mocked useItemsCursor serves; null derives a single
 * page from mockContent (nextCursor null) so the pre-#416 tests that
 * seed mockContent keep rendering their cards in cursor mode.
 */
let mockCursorPages: CursorPage<Item>[] | null = null;

vi.mock('../api/items', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/items')>();
  return {
    // Keep the real listingPhotoUrls — the photo tests exercise it, not a stub.
    ...actual,
    useItems: (
      page: number,
      q = '',
      categoryId: number | null = null,
      filters?: { minPriceCents?: number | null; maxPriceCents?: number | null; sort?: string | null },
    ) => {
      calls.push({ page, q, categoryId, filters });
      const pageData: Page<Item> = {
        content: mockContent,
        totalElements: mockContent.length,
        totalPages: mockTotalPages ?? (mockContent.length === 0 ? 0 : 1),
        number: page,
      };
      return { data: mockItemsLoading ? undefined : pageData, isLoading: mockItemsLoading, isError: false };
    },
    // Suggestions are inert by default (empty list renders no dropdown);
    // the #235 describe seeds mockSuggestions per test.
    useItemSuggestions: () => ({ data: mockSuggestions, isLoading: false, isError: false }),
    // Cursor-mode browse (backlog #416): a stateful stand-in for the
    // infinite query — it serves mockCursorPages one at a time through
    // fetchNextPage, and restarts at the first page when the filter
    // signature changes, mirroring the real hook's new query key.
    useItemsCursor: (
      q = '',
      categoryId: number | null = null,
      filters?: { minPriceCents?: number | null; maxPriceCents?: number | null; sort?: string | null },
      options?: { enabled?: boolean },
    ) => {
      const enabled = options?.enabled ?? true;
      const sig = JSON.stringify([
        q,
        categoryId,
        filters?.minPriceCents ?? null,
        filters?.maxPriceCents ?? null,
      ]);
      cursorCalls.push({ q, categoryId, filters, enabled });
      const [state, setState] = useState({ sig, shown: 1 });
      if (state.sig !== sig) {
        // Filter change: the cursor resets with the result set.
        setState({ sig, shown: 1 });
      }
      const pages = mockCursorPages ?? [{ items: mockContent, nextCursor: null }];
      const shownPages = pages.slice(0, state.shown);
      return {
        data: mockItemsLoading
          ? undefined
          : {
              pages: shownPages,
              pageParams: shownPages.map((_, i) => (i === 0 ? undefined : 'cursor')),
            },
        hasNextPage: shownPages[shownPages.length - 1]?.nextCursor != null,
        isFetchingNextPage: false,
        fetchNextPage: () =>
          setState((s) => ({ ...s, shown: Math.min(s.shown + 1, pages.length) })),
        isLoading: mockItemsLoading,
        isError: false,
      };
    },
  };
});

vi.mock('../api/favorites', () => ({
  // Cards render a FavoriteButton (#143); anonymous in these tests, so it
  // renders nothing — the hooks only need to exist and stay inert.
  useFavorites: () => ({ data: [], isLoading: false, isError: false, isSuccess: true }),
  useToggleFavorite: () => ({ mutate: () => undefined, isLoading: false }),
}));

/** Trail content the mocked useRecentlyViewed serves; empty unless a strip test seeds it. */
let mockRecent: Item[] = [];

vi.mock('../api/recentlyViewed', () => ({
  useRecentlyViewed: () => ({
    data: mockRecent,
    isLoading: false,
    isError: false,
    isSuccess: true,
  }),
}));

vi.mock('../api/categories', () => ({
  useCategories: () => ({
    data: [
      { id: 1, name: 'Textbooks', slug: 'textbooks' },
      { id: 2, name: 'Electronics', slug: 'electronics' },
    ],
    isLoading: false,
    isError: false,
  }),
}));

let currentSearch = '';
function LocationProbe() {
  currentSearch = useLocation().search;
  return null;
}

function renderHome(initialEntry: string) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <LocationProbe />
      <HomePage />
    </MemoryRouter>,
  );
}

describe('HomePage debounced URL-synced search', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    calls.length = 0;
    currentSearch = '';
  });

  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it('hydrates the search box from ?q= on mount (refresh-safe, shareable)', () => {
    renderHome('/?q=bike');

    expect((screen.getByLabelText(/search listings/i) as HTMLInputElement).value).toBe('bike');
    // the committed query flows straight into the items query
    expect(calls[calls.length - 1]).toMatchObject({ q: 'bike' });
  });

  it('debounces typing into the URL and the items query', () => {
    renderHome('/');
    const input = screen.getByLabelText(/search listings/i);

    fireEvent.change(input, { target: { value: 'bike' } });
    fireEvent.change(input, { target: { value: 'bike helmet' } });

    // input updates immediately, but nothing is committed yet
    expect((input as HTMLInputElement).value).toBe('bike helmet');
    expect(currentSearch).toBe('');
    expect(calls[calls.length - 1]).toMatchObject({ q: '' });

    act(() => {
      vi.advanceTimersByTime(300);
    });

    expect(currentSearch).toBe('?q=bike+helmet');
    expect(calls[calls.length - 1]).toMatchObject({ q: 'bike helmet' });
  });

  it('trims the committed value and clears the param when emptied', () => {
    renderHome('/?q=bike');
    const input = screen.getByLabelText(/search listings/i);

    fireEvent.change(input, { target: { value: '   ' } });

    act(() => {
      vi.advanceTimersByTime(300);
    });

    expect(currentSearch).toBe('');
    expect(calls[calls.length - 1]).toMatchObject({ q: '' });
  });

  it('only commits the latest keystroke burst (intermediate timers cancelled)', () => {
    renderHome('/');
    const input = screen.getByLabelText(/search listings/i);

    fireEvent.change(input, { target: { value: 'a' } });
    act(() => {
      vi.advanceTimersByTime(200);
    });
    fireEvent.change(input, { target: { value: 'ab' } });
    act(() => {
      vi.advanceTimersByTime(300);
    });

    expect(currentSearch).toBe('?q=ab');
    // committed once, with the final value
    const committed = calls.map((c) => c.q);
    expect(committed[committed.length - 1]).toBe('ab');
    expect(committed).not.toContain('a');
  });
});

describe('HomePage accessible filter names (backlog #167)', () => {
  beforeEach(() => {
    calls.length = 0;
    currentSearch = '';
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('every browse control is reachable by its label', () => {
    // The browse controls carry sr-only labels (visible labels would
    // break the toolbar layout) — they must stay programmatically
    // bound, or role/label queries (and screen readers) lose them.
    renderHome('/');

    expect(screen.getByLabelText(/search listings/i)).toBeTruthy();
    expect(screen.getByLabelText(/filter by category/i)).toBeTruthy();
    expect(screen.getByLabelText(/minimum price/i)).toBeTruthy();
    expect(screen.getByLabelText(/maximum price/i)).toBeTruthy();
    expect(screen.getByLabelText(/sort listings/i)).toBeTruthy();
  });
});

describe('HomePage category filter', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    calls.length = 0;
    currentSearch = '';
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it('hydrates the dropdown from ?categoryId= on mount (refresh-safe, shareable)', () => {
    renderHome('/?categoryId=2');

    expect((screen.getByLabelText(/filter by category/i) as HTMLSelectElement).value).toBe('2');
    expect(calls[calls.length - 1]).toMatchObject({ categoryId: 2 });
  });

  it('ignores a non-numeric ?categoryId= instead of sending it to the backend', () => {
    renderHome('/?categoryId=abc');

    expect((screen.getByLabelText(/filter by category/i) as HTMLSelectElement).value).toBe('');
    expect(calls[calls.length - 1]).toMatchObject({ categoryId: null });
  });

  it('selecting a category fires the request with the param and syncs the URL', () => {
    renderHome('/');
    const select = screen.getByLabelText(/filter by category/i);

    fireEvent.change(select, { target: { value: '1' } });

    expect(currentSearch).toBe('?categoryId=1');
    expect(calls[calls.length - 1]).toMatchObject({ categoryId: 1 });
  });

  it('choosing "All categories" clears the param and the filter', () => {
    renderHome('/?categoryId=1');
    const select = screen.getByLabelText(/filter by category/i);

    fireEvent.change(select, { target: { value: '' } });

    expect(currentSearch).toBe('');
    expect(calls[calls.length - 1]).toMatchObject({ categoryId: null });
  });

  it('combines with the search box: q and categoryId coexist in URL and request', () => {
    renderHome('/?q=bike');
    const select = screen.getByLabelText(/filter by category/i);

    fireEvent.change(select, { target: { value: '2' } });

    expect(currentSearch).toBe('?q=bike&categoryId=2');
    expect(calls[calls.length - 1]).toMatchObject({ q: 'bike', categoryId: 2 });

    // and typing a new search afterwards keeps the category filter
    const input = screen.getByLabelText(/search listings/i);
    fireEvent.change(input, { target: { value: 'lamp' } });
    act(() => {
      vi.advanceTimersByTime(300);
    });

    expect(currentSearch).toBe('?q=lamp&categoryId=2');
    expect(calls[calls.length - 1]).toMatchObject({ q: 'lamp', categoryId: 2 });
  });
});

describe('HomePage price range + sort', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    calls.length = 0;
    currentSearch = '';
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it('hydrates price bounds and sort from the URL (refresh-safe, shareable)', () => {
    renderHome('/?minPrice=10&maxPrice=50&sort=price-asc');

    expect((screen.getByLabelText(/minimum price/i) as HTMLInputElement).value).toBe('10');
    expect((screen.getByLabelText(/maximum price/i) as HTMLInputElement).value).toBe('50');
    expect((screen.getByLabelText(/sort listings/i) as HTMLSelectElement).value).toBe('price-asc');
    expect(calls[calls.length - 1].filters).toMatchObject({
      minPriceCents: 1000,
      maxPriceCents: 5000,
      sort: 'price-asc',
    });
  });

  it('debounces typed prices into the URL as yuan and the request as cents', () => {
    renderHome('/');
    fireEvent.change(screen.getByLabelText(/minimum price/i), { target: { value: '25' } });

    expect(currentSearch).toBe('');
    act(() => {
      vi.advanceTimersByTime(300);
    });

    expect(currentSearch).toBe('?minPrice=25');
    expect(calls[calls.length - 1].filters).toMatchObject({ minPriceCents: 2500 });
  });

  it('selecting a sort syncs the URL immediately; newest clears the param', () => {
    renderHome('/');
    const select = screen.getByLabelText(/sort listings/i);

    fireEvent.change(select, { target: { value: 'price-desc' } });
    expect(currentSearch).toBe('?sort=price-desc');
    expect(calls[calls.length - 1].filters).toMatchObject({ sort: 'price-desc' });

    fireEvent.change(select, { target: { value: '' } });
    expect(currentSearch).toBe('');
    expect(calls[calls.length - 1].filters).toMatchObject({ sort: 'newest' });
  });

  it('combines q + category + price + sort in URL and request', () => {
    renderHome('/?q=bike&categoryId=2&minPrice=10&sort=price-asc');

    expect(currentSearch).toBe('?q=bike&categoryId=2&minPrice=10&sort=price-asc');
    expect(calls[calls.length - 1]).toMatchObject({ q: 'bike', categoryId: 2 });
    expect(calls[calls.length - 1].filters).toMatchObject({
      minPriceCents: 1000,
      sort: 'price-asc',
    });
  });

  it('treats a non-numeric price in the URL as no bound instead of sending NaN', () => {
    renderHome('/?minPrice=abc');

    expect(calls[calls.length - 1].filters).toMatchObject({ minPriceCents: null });
  });
});

describe('HomePage listing photos', () => {
  const baseItem: Item = {
    id: 7,
    title: 'Used bike',
    description: 'Rides fine',
    priceCents: 12000,
    status: 'AVAILABLE',
    sellerId: 3,
    sellerUsername: 'seller',
    categoryId: null,
    categoryName: null,
    photoUrl: null,
    photos: [],
  };

  beforeEach(() => {
    mockContent = [];
    currentSearch = '';
  });

  afterEach(() => {
    cleanup();
    mockContent = [];
    vi.clearAllMocks();
  });

  it('renders the primary (lowest-position) gallery photo on the card', () => {
    mockContent = [
      {
        ...baseItem,
        photos: [
          { id: 2, url: '/uploads/b.png', position: 1 },
          { id: 1, url: '/uploads/a.png', position: 0 },
        ],
      },
    ];
    renderHome('/');

    const img = screen.getByRole('img', { name: 'Used bike' }) as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('/uploads/a.png');
  });

  it('renders the legacy photoUrl when the gallery is empty', () => {
    mockContent = [{ ...baseItem, photoUrl: '/uploads/legacy.png' }];
    renderHome('/');

    const img = screen.getByRole('img', { name: 'Used bike' }) as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('/uploads/legacy.png');
  });

  it('renders an honest placeholder and no img for a photo-less listing', () => {
    mockContent = [baseItem];
    const { container } = renderHome('/');

    expect(screen.getByTestId('no-photo-placeholder').textContent).toBe('No photo');
    expect(container.querySelector('img')).toBeNull();
  });
});

describe('HomePage pagination (backlog #144)', () => {
  // Backlog #416: the newest-first browse is cursor-based ("Load more"),
  // so these offset-paging tests run under a price sort — the only mode
  // that still pages by ?page= with Prev/Next controls.
  const pagedItem: Item = {
    id: 11,
    title: 'Paged bike',
    description: 'Rides fine',
    priceCents: 12000,
    status: 'AVAILABLE',
    sellerId: 3,
    sellerUsername: 'seller',
    categoryId: null,
    categoryName: null,
    photoUrl: null,
    photos: [],
  };

  beforeEach(() => {
    vi.useFakeTimers();
    calls.length = 0;
    currentSearch = '';
    mockContent = [pagedItem];
    mockTotalPages = 3;
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.clearAllMocks();
    mockContent = [];
    mockTotalPages = null;
  });

  it('direct load of ?page=2 renders that page (refresh-safe, shareable)', () => {
    renderHome('/?page=2&sort=price-asc');

    expect(calls[calls.length - 1]).toMatchObject({ page: 2 });
    expect(screen.getByText('Page 3 of 3')).toBeTruthy();
  });

  it('Next requests page 1 and syncs ?page= into the URL', () => {
    renderHome('/?sort=price-asc');

    expect(screen.getByText('Page 1 of 3')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /next/i }));

    expect(currentSearch).toBe('?sort=price-asc&page=1');
    expect(calls[calls.length - 1]).toMatchObject({ page: 1 });
    expect(screen.getByText('Page 2 of 3')).toBeTruthy();
  });

  it('Previous from ?page=2 returns to page 1, and back to page 0 clears the param', () => {
    renderHome('/?page=2&sort=price-asc');

    fireEvent.click(screen.getByRole('button', { name: /previous/i }));
    expect(currentSearch).toBe('?page=1&sort=price-asc');
    expect(calls[calls.length - 1]).toMatchObject({ page: 1 });

    fireEvent.click(screen.getByRole('button', { name: /previous/i }));
    expect(currentSearch).toBe('?sort=price-asc');
    expect(calls[calls.length - 1]).toMatchObject({ page: 0 });
  });

  it('disables Previous on the first page and Next on the last page', () => {
    renderHome('/?sort=price-asc');
    expect((screen.getByRole('button', { name: /previous/i }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: /next/i }) as HTMLButtonElement).disabled).toBe(false);

    cleanup();
    calls.length = 0;
    renderHome('/?page=2&sort=price-asc');
    expect((screen.getByRole('button', { name: /previous/i }) as HTMLButtonElement).disabled).toBe(false);
    expect((screen.getByRole('button', { name: /next/i }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('a category change drops ?page= so the new result set starts at page 0', () => {
    renderHome('/?page=2&sort=price-asc');

    fireEvent.change(screen.getByLabelText(/filter by category/i), { target: { value: '1' } });

    expect(currentSearch).toBe('?sort=price-asc&categoryId=1');
    expect(calls[calls.length - 1]).toMatchObject({ page: 0, categoryId: 1 });
  });

  it('a sort change drops ?page=', () => {
    renderHome('/?page=1');

    fireEvent.change(screen.getByLabelText(/sort listings/i), { target: { value: 'price-asc' } });

    expect(currentSearch).toBe('?sort=price-asc');
    expect(calls[calls.length - 1]).toMatchObject({ page: 0 });
  });

  it('a committed search drops ?page= after the debounce', () => {
    renderHome('/?page=2');

    fireEvent.change(screen.getByLabelText(/search listings/i), { target: { value: 'lamp' } });
    act(() => {
      vi.advanceTimersByTime(300);
    });

    expect(currentSearch).toBe('?q=lamp');
    expect(calls[calls.length - 1]).toMatchObject({ page: 0, q: 'lamp' });
  });

  it('a committed price filter drops ?page= after the debounce', () => {
    renderHome('/?page=1');

    fireEvent.change(screen.getByLabelText(/minimum price/i), { target: { value: '25' } });
    act(() => {
      vi.advanceTimersByTime(300);
    });

    expect(currentSearch).toBe('?minPrice=25');
    expect(calls[calls.length - 1]).toMatchObject({ page: 0 });
  });

  it('treats a non-numeric or negative ?page= as page 0 instead of sending it on', () => {
    renderHome('/?page=abc');
    expect(calls[calls.length - 1]).toMatchObject({ page: 0 });

    cleanup();
    calls.length = 0;
    renderHome('/?page=-1');
    expect(calls[calls.length - 1]).toMatchObject({ page: 0 });
  });
});

describe('HomePage recently viewed strip (#195)', () => {
  const buyer: AuthUser = {
    id: 5,
    username: 'buyer',
    email: 'buyer@example.com',
    roles: ['USER'],
  };
  const viewedItem: Item = {
    id: 42,
    title: 'Trail bike',
    description: 'Seen it before',
    priceCents: 9900,
    status: 'AVAILABLE',
    sellerId: 3,
    sellerUsername: 'seller',
    categoryId: null,
    categoryName: null,
    photoUrl: null,
    photos: [],
  };

  beforeEach(() => {
    calls.length = 0;
    currentSearch = '';
    mockRecent = [];
    mockContent = [];
  });

  afterEach(() => {
    cleanup();
    useAuthStore.setState({ user: null });
    vi.clearAllMocks();
  });

  it('renders the strip with the browse card shape for a signed-in visitor', () => {
    useAuthStore.setState({ user: buyer });
    mockRecent = [viewedItem];

    renderHome('/');

    const strip = screen.getByRole('region', { name: /recently viewed/i });
    expect(strip).toBeTruthy();
    expect(screen.getByRole('heading', { name: /recently viewed/i })).toBeTruthy();
    const link = screen.getByRole('link', { name: 'Trail bike' });
    expect(link.getAttribute('href')).toBe('/items/42');
  });

  it('hides the strip when the trail is empty', () => {
    useAuthStore.setState({ user: buyer });
    mockRecent = [];

    renderHome('/');

    expect(screen.queryByRole('region', { name: /recently viewed/i })).toBeNull();
    expect(screen.queryByRole('heading', { name: /recently viewed/i })).toBeNull();
  });

  it('hides the strip for anonymous visitors even with trail data', () => {
    mockRecent = [viewedItem];

    renderHome('/');

    expect(screen.queryByRole('heading', { name: /recently viewed/i })).toBeNull();
  });
});

describe('HomePage search suggestions dropdown (backlog #235)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    calls.length = 0;
    currentSearch = '';
    mockSuggestions = [];
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.clearAllMocks();
    mockSuggestions = [];
  });

  function typeAndDebounce(value: string) {
    const input = screen.getByLabelText(/search listings/i);
    fireEvent.change(input, { target: { value } });
    act(() => {
      vi.advanceTimersByTime(300);
    });
    return input;
  }

  it('renders the dropdown after debounced typing', () => {
    mockSuggestions = [
      { id: 1, title: 'Bike helmet' },
      { id: 2, title: 'Bike lock' },
    ];
    renderHome('/');

    typeAndDebounce('bike');

    expect(screen.getByRole('listbox', { name: /search suggestions/i })).toBeTruthy();
    expect(screen.getByRole('option', { name: 'Bike helmet' })).toBeTruthy();
    expect(screen.getByRole('option', { name: 'Bike lock' })).toBeTruthy();
  });

  it('selecting a suggestion navigates to the ?q= results and closes the dropdown', () => {
    mockSuggestions = [
      { id: 1, title: 'Bike helmet' },
      { id: 2, title: 'Bike lock' },
    ];
    renderHome('/');

    const input = typeAndDebounce('bike');
    fireEvent.click(screen.getByRole('option', { name: 'Bike lock' }));

    expect((input as HTMLInputElement).value).toBe('Bike lock');
    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(currentSearch).toBe('?q=Bike+lock');
    expect(screen.queryByRole('listbox')).toBeNull();
  });

  it('Escape dismisses the dropdown', () => {
    mockSuggestions = [{ id: 1, title: 'Bike helmet' }];
    renderHome('/');

    const input = typeAndDebounce('bike');
    expect(screen.getByRole('listbox')).toBeTruthy();

    fireEvent.keyDown(input, { key: 'Escape' });
    expect(screen.queryByRole('listbox')).toBeNull();
  });

  it('renders no dropdown when there are no suggestions', () => {
    renderHome('/');

    typeAndDebounce('bike');

    expect(screen.queryByRole('listbox')).toBeNull();
  });
});

describe('HomePage cursor pagination (backlog #416)', () => {
  const cursorItem = (id: number, title: string): Item => ({
    id,
    title,
    description: null,
    priceCents: 1000,
    status: 'AVAILABLE',
    sellerId: 3,
    sellerUsername: 'seller',
    categoryId: null,
    categoryName: null,
    photoUrl: null,
    photos: [],
  });

  beforeEach(() => {
    vi.useFakeTimers();
    calls.length = 0;
    cursorCalls.length = 0;
    currentSearch = '';
    mockContent = [];
    mockCursorPages = null;
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.clearAllMocks();
    mockContent = [];
    mockCursorPages = null;
    mockTotalPages = null;
  });

  function browseTitles(): string[] {
    return screen
      .getAllByRole('listitem')
      .map((li) => li.textContent ?? '')
      .filter((text) => text.length > 0);
  }

  it('walks two cursor pages with Load more — no dup, no gap, button gone at the end', () => {
    mockCursorPages = [
      { items: [cursorItem(1, 'Bike A'), cursorItem(2, 'Bike B')], nextCursor: 'c1' },
      { items: [cursorItem(2, 'Bike B'), cursorItem(3, 'Bike C')], nextCursor: null },
    ];
    renderHome('/');

    // First page only; the second page's unique row is not there yet.
    expect(browseTitles().join(' ')).toMatch(/Bike A/);
    expect(browseTitles().join(' ')).toMatch(/Bike B/);
    expect(browseTitles().join(' ')).not.toMatch(/Bike C/);
    expect(screen.getByRole('button', { name: /load more/i })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /load more/i }));

    // Both pages stitched in order; the row repeated across the page
    // boundary (Bike B) renders exactly once; the button is gone.
    const titles = browseTitles();
    expect(titles.filter((t) => /Bike B/.test(t))).toHaveLength(1);
    expect(titles.findIndex((t) => /Bike A/.test(t))).toBeLessThan(
      titles.findIndex((t) => /Bike B/.test(t)),
    );
    expect(titles.findIndex((t) => /Bike B/.test(t))).toBeLessThan(
      titles.findIndex((t) => /Bike C/.test(t)),
    );
    expect(screen.queryByRole('button', { name: /load more/i })).toBeNull();
  });

  it('a filter change resets the cursor — the walk restarts at the first page', () => {
    mockCursorPages = [
      { items: [cursorItem(1, 'Bike A')], nextCursor: 'c1' },
      { items: [cursorItem(2, 'Lamp B')], nextCursor: null },
    ];
    renderHome('/');

    fireEvent.click(screen.getByRole('button', { name: /load more/i }));
    expect(browseTitles().join(' ')).toMatch(/Lamp B/);

    const input = screen.getByLabelText(/search listings/i);
    fireEvent.change(input, { target: { value: 'lamp' } });
    act(() => {
      vi.advanceTimersByTime(300);
    });

    // The committed filter restarts the cursor walk: only the first
    // page's rows show again, and the new query carries the filter.
    expect(currentSearch).toBe('?q=lamp');
    expect(browseTitles().join(' ')).toMatch(/Bike A/);
    expect(browseTitles().join(' ')).not.toMatch(/Lamp B/);
    expect(cursorCalls[cursorCalls.length - 1]).toMatchObject({ q: 'lamp' });
    expect(screen.getByRole('button', { name: /load more/i })).toBeTruthy();
  });

  it('a price sort stays on offset paging — no Load more, Prev/Next instead', () => {
    mockContent = [cursorItem(11, 'Paged bike')];
    mockTotalPages = 3;
    renderHome('/?sort=price-asc');

    expect(screen.getByText('Page 1 of 3')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /load more/i })).toBeNull();
    // The cursor hook is mounted but disabled, so it fetches nothing;
    // the offset hook serves the page.
    expect(cursorCalls[cursorCalls.length - 1].enabled).toBe(false);
    expect(calls[calls.length - 1]).toMatchObject({ page: 0 });
  });

  it('the newest sort serves the cursor query with the filters composed', () => {
    renderHome('/?q=bike&categoryId=2&minPrice=10');

    expect(cursorCalls[cursorCalls.length - 1]).toMatchObject({
      q: 'bike',
      categoryId: 2,
      enabled: true,
    });
    expect(cursorCalls[cursorCalls.length - 1].filters).toMatchObject({
      minPriceCents: 1000,
    });
  });
});

describe('HomePage skeleton loading states (backlog #427)', () => {
  const skeletonItem: Item = {
    id: 21,
    title: 'Skeleton bike',
    description: 'Rides fine',
    priceCents: 12000,
    status: 'AVAILABLE',
    sellerId: 3,
    sellerUsername: 'seller',
    categoryId: null,
    categoryName: null,
    photoUrl: null,
    photos: [],
  };

  beforeEach(() => {
    calls.length = 0;
    cursorCalls.length = 0;
    currentSearch = '';
    mockContent = [];
    mockCursorPages = null;
    mockItemsLoading = false;
  });

  afterEach(() => {
    cleanup();
    mockContent = [];
    mockCursorPages = null;
    mockTotalPages = null;
    mockItemsLoading = false;
    vi.clearAllMocks();
  });

  it('renders skeleton cards while the cursor query is pending — no bare loading text', () => {
    mockItemsLoading = true;
    renderHome('/');

    const status = screen.getByRole('status', { name: /loading listings/i });
    expect(status.getAttribute('aria-busy')).toBe('true');
    expect(screen.getAllByTestId('listing-card-skeleton')).toHaveLength(6);
    // The old bare `<p>Loading listings…</p>` is gone — the only copy is
    // the sr-only label inside the status region.
    expect(status.querySelector('p')).toBeNull();
    expect(screen.queryByRole('link', { name: 'Skeleton bike' })).toBeNull();
  });

  it('renders skeleton cards while the price-sort offset query is pending', () => {
    mockItemsLoading = true;
    renderHome('/?sort=price-asc');

    expect(screen.getByRole('status', { name: /loading listings/i })).toBeTruthy();
    expect(screen.getAllByTestId('listing-card-skeleton')).toHaveLength(6);
  });

  it('real content replaces the skeletons once the query resolves', () => {
    mockItemsLoading = true;
    const { rerender } = renderHome('/');

    expect(screen.getAllByTestId('listing-card-skeleton')).toHaveLength(6);

    mockItemsLoading = false;
    mockContent = [skeletonItem];
    rerender(
      <MemoryRouter initialEntries={['/']}>
        <LocationProbe />
        <HomePage />
      </MemoryRouter>,
    );

    expect(screen.queryByRole('status', { name: /loading listings/i })).toBeNull();
    expect(screen.queryByTestId('listing-card-skeleton')).toBeNull();
    expect(screen.getByRole('link', { name: 'Skeleton bike' })).toBeTruthy();
  });
});
