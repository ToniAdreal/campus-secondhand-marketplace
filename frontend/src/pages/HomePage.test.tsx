import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import HomePage from './HomePage';
import type { Page, Item } from '../api/items';

const calls: Array<{
  page: number;
  q: string;
  categoryId: number | null;
  filters?: { minPriceCents?: number | null; maxPriceCents?: number | null; sort?: string | null };
}> = [];

/** Content the mocked useItems serves; empty unless a test seeds listings. */
let mockContent: Item[] = [];

/** Page-count override for the pagination tests; null derives it from content. */
let mockTotalPages: number | null = null;

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
      return { data: pageData, isLoading: false, isError: false };
    },
  };
});

vi.mock('../api/favorites', () => ({
  // Cards render a FavoriteButton (#143); anonymous in these tests, so it
  // renders nothing — the hooks only need to exist and stay inert.
  useFavorites: () => ({ data: [], isLoading: false, isError: false, isSuccess: true }),
  useToggleFavorite: () => ({ mutate: () => undefined, isLoading: false }),
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
    renderHome('/?page=2');

    expect(calls[calls.length - 1]).toMatchObject({ page: 2 });
    expect(screen.getByText('Page 3 of 3')).toBeTruthy();
  });

  it('Next requests page 1 and syncs ?page= into the URL', () => {
    renderHome('/');

    expect(screen.getByText('Page 1 of 3')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /next/i }));

    expect(currentSearch).toBe('?page=1');
    expect(calls[calls.length - 1]).toMatchObject({ page: 1 });
    expect(screen.getByText('Page 2 of 3')).toBeTruthy();
  });

  it('Previous from ?page=2 returns to page 1, and back to page 0 clears the param', () => {
    renderHome('/?page=2');

    fireEvent.click(screen.getByRole('button', { name: /previous/i }));
    expect(currentSearch).toBe('?page=1');
    expect(calls[calls.length - 1]).toMatchObject({ page: 1 });

    fireEvent.click(screen.getByRole('button', { name: /previous/i }));
    expect(currentSearch).toBe('');
    expect(calls[calls.length - 1]).toMatchObject({ page: 0 });
  });

  it('disables Previous on the first page and Next on the last page', () => {
    renderHome('/');
    expect((screen.getByRole('button', { name: /previous/i }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: /next/i }) as HTMLButtonElement).disabled).toBe(false);

    cleanup();
    calls.length = 0;
    renderHome('/?page=2');
    expect((screen.getByRole('button', { name: /previous/i }) as HTMLButtonElement).disabled).toBe(false);
    expect((screen.getByRole('button', { name: /next/i }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('a category change drops ?page= so the new result set starts at page 0', () => {
    renderHome('/?page=2');

    fireEvent.change(screen.getByLabelText(/filter by category/i), { target: { value: '1' } });

    expect(currentSearch).toBe('?categoryId=1');
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
