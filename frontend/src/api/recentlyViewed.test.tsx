import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import { useRecentlyViewed } from './recentlyViewed';
import type { Item } from './items';

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

const viewedItem: Item = {
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

describe('recently viewed api hook (#195)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('GETs /users/me/recently-viewed and returns the trail from the envelope', async () => {
    getSpy.mockResolvedValueOnce(envelope([viewedItem]));

    const { result } = renderHook(() => useRecentlyViewed(), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/users/me/recently-viewed');
    expect(result.current.data).toEqual([viewedItem]);
  });

  it('does not fetch while disabled (anonymous)', async () => {
    const { result } = renderHook(() => useRecentlyViewed(false), {
      wrapper: wrapperFor(makeClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('degrades a malformed payload to an empty trail', async () => {
    getSpy.mockResolvedValueOnce(envelope({ nope: true }));

    const { result } = renderHook(() => useRecentlyViewed(), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toEqual([]);
  });
});
