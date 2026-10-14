import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import {
  describeSavedSearch,
  savedSearchBrowsePath,
  useCreateSavedSearch,
  useDeleteSavedSearch,
  useSavedSearches,
  type SavedSearch,
} from './savedSearches';

const getSpy = vi.spyOn(api, 'get');
const postSpy = vi.spyOn(api, 'post');
const deleteSpy = vi.spyOn(api, 'delete');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function wrapperFor(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

const savedSearch: SavedSearch = {
  id: 9,
  keyword: 'vintage bike',
  categoryId: 3,
  minPriceCents: 1000,
  maxPriceCents: 1050,
  sort: 'price-asc',
  matchCount: 4,
  createdAt: '2026-10-14T00:00:00Z',
};

describe('saved searches api hooks (#164)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('GETs /users/me/saved-searches and returns the rows from the envelope', async () => {
    getSpy.mockResolvedValueOnce(envelope([savedSearch]));

    const { result } = renderHook(() => useSavedSearches(), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/users/me/saved-searches');
    expect(result.current.data).toEqual([savedSearch]);
    expect(result.current.data?.[0].matchCount).toBe(4);
  });

  it('does not fetch while disabled (anonymous)', async () => {
    const { result } = renderHook(() => useSavedSearches(false), {
      wrapper: wrapperFor(makeClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('create POSTs the discrete filter set and invalidates the list', async () => {
    postSpy.mockResolvedValue(envelope(savedSearch));
    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useCreateSavedSearch(), {
      wrapper: wrapperFor(client),
    });

    const created = await result.current.mutateAsync({
      keyword: 'vintage bike',
      categoryId: 3,
      minPriceCents: 1000,
      maxPriceCents: 1050,
      sort: 'price-asc',
    });

    expect(postSpy).toHaveBeenCalledWith('/users/me/saved-searches', {
      keyword: 'vintage bike',
      categoryId: 3,
      minPriceCents: 1000,
      maxPriceCents: 1050,
      sort: 'price-asc',
    });
    expect(created).toEqual(savedSearch);
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['saved-searches'] });
  });

  it('create sends nulls for unset filters', async () => {
    postSpy.mockResolvedValue(envelope(savedSearch));

    const { result } = renderHook(() => useCreateSavedSearch(), {
      wrapper: wrapperFor(makeClient()),
    });
    await result.current.mutateAsync({ keyword: 'bike' });

    expect(postSpy).toHaveBeenCalledWith('/users/me/saved-searches', {
      keyword: 'bike',
      categoryId: null,
      minPriceCents: null,
      maxPriceCents: null,
      sort: null,
    });
  });

  it('delete DELETEs by id and invalidates the list', async () => {
    deleteSpy.mockResolvedValue(envelope(null));
    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useDeleteSavedSearch(), {
      wrapper: wrapperFor(client),
    });
    await result.current.mutateAsync(9);

    expect(deleteSpy).toHaveBeenCalledWith('/users/me/saved-searches/9');
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['saved-searches'] });
  });
});

describe('savedSearchBrowsePath (#164)', () => {
  it('restores the exact HomePage params (price back to yuan, sort kept)', () => {
    expect(savedSearchBrowsePath(savedSearch)).toBe(
      '/?q=vintage+bike&categoryId=3&minPrice=10&maxPrice=10.5&sort=price-asc',
    );
  });

  it('omits the default sort and unset filters', () => {
    expect(
      savedSearchBrowsePath({ ...savedSearch, categoryId: null, minPriceCents: null, maxPriceCents: null, sort: 'newest' }),
    ).toBe('/?q=vintage+bike');
    expect(
      savedSearchBrowsePath({
        ...savedSearch,
        keyword: null,
        categoryId: null,
        minPriceCents: null,
        maxPriceCents: null,
        sort: 'newest',
      }),
    ).toBe('/');
  });

  it('formats whole-yuan and odd-cent bounds exactly', () => {
    expect(
      savedSearchBrowsePath({ ...savedSearch, keyword: null, categoryId: null, minPriceCents: 1001, maxPriceCents: 2000, sort: 'newest' }),
    ).toBe('/?minPrice=10.01&maxPrice=20');
  });
});

describe('describeSavedSearch (#164)', () => {
  it('summarizes the filter set for the list rows', () => {
    expect(describeSavedSearch(savedSearch)).toBe(
      '"vintage bike" · Category #3 · from ¥10 · up to ¥10.5 · Price: low to high',
    );
    expect(
      describeSavedSearch({
        ...savedSearch,
        keyword: null,
        categoryId: null,
        minPriceCents: null,
        maxPriceCents: null,
        sort: 'newest',
      }),
    ).toBe('All listings');
  });
});
