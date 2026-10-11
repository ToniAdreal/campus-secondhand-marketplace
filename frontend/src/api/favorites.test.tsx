import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import { useFavorites, useToggleFavorite } from './favorites';
import type { Item } from './items';

const getSpy = vi.spyOn(api, 'get');
const putSpy = vi.spyOn(api, 'put');
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

const savedItem: Item = {
  id: 7,
  title: 'Used bike',
  description: 'Rides fine',
  priceCents: 12000,
  status: 'SOLD',
  sellerId: 3,
  sellerUsername: 'seller',
  categoryId: null,
  categoryName: null,
  photoUrl: null,
  photos: [],
};

describe('favorites api hooks (#143)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('GETs /users/me/favorites and returns the saved list from the envelope', async () => {
    getSpy.mockResolvedValueOnce(envelope([savedItem]));

    const { result } = renderHook(() => useFavorites(), { wrapper: wrapperFor(makeClient()) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/users/me/favorites');
    expect(result.current.data).toEqual([savedItem]);
    // A saved SOLD listing stays listed with its status — never filtered out.
    expect(result.current.data?.[0].status).toBe('SOLD');
  });

  it('does not fetch while disabled (anonymous)', async () => {
    const { result } = renderHook(() => useFavorites(false), {
      wrapper: wrapperFor(makeClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('toggle PUTs to save and DELETEs to unsave, invalidating the list', async () => {
    putSpy.mockResolvedValue(envelope(null));
    deleteSpy.mockResolvedValue(envelope(null));
    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useToggleFavorite(), { wrapper: wrapperFor(client) });

    await result.current.mutateAsync({ itemId: 7, saved: false });
    expect(putSpy).toHaveBeenCalledWith('/users/me/favorites/7');

    await result.current.mutateAsync({ itemId: 7, saved: true });
    expect(deleteSpy).toHaveBeenCalledWith('/users/me/favorites/7');

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['favorites'] });
  });
});
