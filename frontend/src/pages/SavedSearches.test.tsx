import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import HomePage from './HomePage';
import FavoritesPage from './FavoritesPage';
import type { SavedSearch, SavedSearchInput } from '../api/savedSearches';
import type { AuthUser } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';

const createCalls: SavedSearchInput[] = [];
const deleteCalls: number[] = [];
let mockSavedSearches: SavedSearch[] = [];

vi.mock('../api/items', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/items')>();
  return {
    ...actual,
    useItems: () => ({
      data: { content: [], totalElements: 0, totalPages: 0, number: 0 },
      isLoading: false,
      isError: false,
    }),
  };
});

vi.mock('../api/favorites', () => ({
  useFavorites: () => ({ data: [], isLoading: false, isError: false, isSuccess: true }),
  useToggleFavorite: () => ({ mutate: () => undefined, isLoading: false }),
}));

vi.mock('../api/recentlyViewed', () => ({
  // HomePage renders the trail strip (#195); inert and empty here —
  // these tests exercise saved searches, not the trail.
  useRecentlyViewed: () => ({ data: [], isLoading: false, isError: false, isSuccess: true }),
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

vi.mock('../api/savedSearches', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/savedSearches')>();
  return {
    // Keep the real savedSearchBrowsePath / describeSavedSearch — the
    // restore test exercises the exact URL the row links to.
    ...actual,
    useSavedSearches: () => ({
      data: mockSavedSearches,
      isLoading: false,
      isError: false,
      isSuccess: true,
    }),
    useCreateSavedSearch: () => ({
      mutate: (input: SavedSearchInput, opts?: { onSuccess?: () => void }) => {
        createCalls.push(input);
        opts?.onSuccess?.();
      },
      isLoading: false,
      isError: false,
    }),
    useDeleteSavedSearch: () => ({
      mutate: (id: number) => {
        deleteCalls.push(id);
      },
      isLoading: false,
    }),
  };
});

const buyer: AuthUser = { id: 5, username: 'buyer', email: 'buyer@example.com', roles: ['USER'] };

const vintageSearch: SavedSearch = {
  id: 9,
  keyword: 'vintage',
  categoryId: 2,
  minPriceCents: 1000,
  maxPriceCents: 2050,
  sort: 'price-asc',
  matchCount: 3,
  createdAt: '2026-10-14T00:00:00Z',
};

let currentSearch = '';
function LocationProbe() {
  currentSearch = useLocation().search;
  return null;
}

describe('saved searches (#164)', () => {
  beforeEach(() => {
    createCalls.length = 0;
    deleteCalls.length = 0;
    mockSavedSearches = [];
    currentSearch = '';
    useAuthStore.setState({ user: buyer });
  });

  afterEach(() => {
    cleanup();
    useAuthStore.setState({ user: null });
    vi.clearAllMocks();
  });

  it('HomePage saves the exact active filter set from the URL', () => {
    render(
      <MemoryRouter
        initialEntries={['/?q=bike&categoryId=2&minPrice=10&maxPrice=20.5&sort=price-asc']}
      >
        <HomePage />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: /save this search/i }));

    // URL price params are yuan as typed; the save carries cents.
    expect(createCalls).toEqual([
      {
        keyword: 'bike',
        categoryId: 2,
        minPriceCents: 1000,
        maxPriceCents: 2050,
        sort: 'price-asc',
      },
    ]);
    expect(screen.getByText(/saved — find it under saved searches/i)).toBeTruthy();
  });

  it('HomePage offers no save button with no active filter, or signed out', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <HomePage />
      </MemoryRouter>,
    );
    expect(screen.queryByRole('button', { name: /save this search/i })).toBeNull();

    cleanup();
    useAuthStore.setState({ user: null });
    render(
      <MemoryRouter initialEntries={['/?q=bike']}>
        <HomePage />
      </MemoryRouter>,
    );
    expect(screen.queryByRole('button', { name: /save this search/i })).toBeNull();
  });

  it('a non-default sort alone is enough to offer the save button', () => {
    render(
      <MemoryRouter initialEntries={['/?sort=price-desc']}>
        <HomePage />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: /save this search/i }));

    expect(createCalls).toEqual([
      { keyword: null, categoryId: null, minPriceCents: null, maxPriceCents: null, sort: 'price-desc' },
    ]);
  });

  it('FavoritesPage lists saved searches with matchCount, restores exact params, and deletes', () => {
    mockSavedSearches = [vintageSearch];
    render(
      <MemoryRouter initialEntries={['/favorites']}>
        <LocationProbe />
        <FavoritesPage />
      </MemoryRouter>,
    );

    expect(screen.getByText('3 matching listings')).toBeTruthy();

    // Clicking the row restores the exact browse URL HomePage parses:
    // q / categoryId / minPrice / maxPrice in yuan / sort.
    const link = screen.getByRole('link', { name: /vintage/i });
    expect(link.getAttribute('href')).toBe(
      '/?q=vintage&categoryId=2&minPrice=10&maxPrice=20.5&sort=price-asc',
    );
    fireEvent.click(link);
    expect(currentSearch).toBe('?q=vintage&categoryId=2&minPrice=10&maxPrice=20.5&sort=price-asc');

    fireEvent.click(screen.getByRole('button', { name: /delete saved search/i }));
    expect(deleteCalls).toEqual([9]);
  });

  it('FavoritesPage shows the saved-searches empty state', () => {
    render(
      <MemoryRouter initialEntries={['/favorites']}>
        <FavoritesPage />
      </MemoryRouter>,
    );

    expect(screen.getByText(/haven't saved any searches yet/i)).toBeTruthy();
  });
});
