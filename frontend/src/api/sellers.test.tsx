import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import { useSellerListings, useSellerProfile, type SellerProfile } from './sellers';
import type { Item, Page } from './items';

const getSpy = vi.spyOn(api, 'get');

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

const profile: SellerProfile = {
  username: 'seller',
  memberSince: '2026-09-01T00:00:00Z',
  availableListingCount: 2,
  completedSalesCount: 5,
};

const liveItem: Item = {
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

describe('sellers api hooks (#174)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('GETs /users/{id}/profile and returns the profile from the envelope', async () => {
    getSpy.mockResolvedValueOnce(envelope(profile));

    const { result } = renderHook(() => useSellerProfile(3), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/users/3/profile');
    expect(result.current.data).toEqual(profile);
  });

  it('GETs /users/{id}/listings with the first page and returns the page', async () => {
    const page: Page<Item> = { content: [liveItem], totalElements: 1, totalPages: 1, number: 0 };
    getSpy.mockResolvedValueOnce(envelope(page));

    const { result } = renderHook(() => useSellerListings(3), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/users/3/listings', {
      params: { page: 0, size: 20 },
    });
    expect(result.current.data).toEqual(page);
  });

  it('does not fetch for a non-positive id', async () => {
    const { result } = renderHook(() => useSellerProfile(0), {
      wrapper: wrapperFor(makeClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });
});
