import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import { itemKeys, listingPhotoUrls, useItems, type ItemPhoto } from './items';

const getSpy = vi.spyOn(api, 'get');

function envelopePage() {
  return {
    data: {
      code: 0,
      message: 'ok',
      data: { content: [], totalElements: 0, totalPages: 0, number: 0 },
    },
  } as never;
}

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

describe('useItems request params', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('omits q and categoryId when neither filter is set', async () => {
    getSpy.mockResolvedValue(envelopePage());

    const { result } = renderHook(() => useItems(0), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/items', { params: { page: 0, size: 20 } });
  });

  it('sends categoryId on its own', async () => {
    getSpy.mockResolvedValue(envelopePage());

    const { result } = renderHook(() => useItems(0, '', 3), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/items', {
      params: { page: 0, size: 20, categoryId: 3 },
    });
  });

  it('sends q and categoryId together (the backend ANDs both filters)', async () => {
    getSpy.mockResolvedValue(envelopePage());

    const { result } = renderHook(() => useItems(0, 'bike', 2), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/items', {
      params: { page: 0, size: 20, q: 'bike', categoryId: 2 },
    });
  });

  it('treats a null categoryId as no filter', async () => {
    getSpy.mockResolvedValue(envelopePage());

    const { result } = renderHook(() => useItems(0, '', null), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/items', { params: { page: 0, size: 20 } });
  });

  it('sends price bounds and a non-default sort, omitting the default sort', async () => {
    getSpy.mockResolvedValue(envelopePage());

    const { result } = renderHook(
      () => useItems(0, '', null, { minPriceCents: 500, maxPriceCents: 5000, sort: 'price-asc' }),
      { wrapper },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/items', {
      params: { page: 0, size: 20, minPriceCents: 500, maxPriceCents: 5000, sort: 'price-asc' },
    });
  });

  it('omits sort=newest (the backend default) from the request', async () => {
    getSpy.mockResolvedValue(envelopePage());

    const { result } = renderHook(() => useItems(0, '', null, { sort: 'newest' }), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/items', { params: { page: 0, size: 20 } });
  });

  it('keys the cache by category so filtered and unfiltered lists never collide', () => {
    expect(itemKeys.list(0, '')).not.toEqual(itemKeys.list(0, '', 2));
    expect(itemKeys.list(0, 'bike', 2)).not.toEqual(itemKeys.list(0, 'bike'));
  });
});

describe('useItems keepPreviousData (backlog #144)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('keeps the previous page visible while the next page loads', async () => {
    const page0 = {
      data: {
        code: 0,
        message: 'ok',
        data: { content: [{ id: 1 }], totalElements: 21, totalPages: 2, number: 0 },
      },
    } as never;
    let resolvePage1: (value: never) => void = () => undefined;
    const page1Pending = new Promise<never>((resolve) => {
      resolvePage1 = resolve;
    });
    getSpy.mockResolvedValueOnce(page0).mockReturnValueOnce(page1Pending);

    const { result, rerender } = renderHook(({ page }: { page: number }) => useItems(page), {
      wrapper,
      initialProps: { page: 0 },
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.number).toBe(0);

    rerender({ page: 1 });

    // The page-1 request is still in flight, but the page-0 rows stay.
    expect(result.current.data?.number).toBe(0);
    expect(result.current.isPreviousData).toBe(true);

    resolvePage1({
      data: {
        code: 0,
        message: 'ok',
        data: { content: [{ id: 21 }], totalElements: 21, totalPages: 2, number: 1 },
      },
    } as never);
    await waitFor(() => expect(result.current.data?.number).toBe(1));
    expect(result.current.isPreviousData).toBe(false);
  });
});

describe('listingPhotoUrls', () => {
  const photo = (id: number, url: string, position: number): ItemPhoto => ({ id, url, position });

  it('returns the gallery ordered by position, primary first', () => {
    expect(
      listingPhotoUrls({
        photoUrl: null,
        photos: [photo(2, '/uploads/b.png', 1), photo(1, '/uploads/a.png', 0)],
      }),
    ).toEqual(['/uploads/a.png', '/uploads/b.png']);
  });

  it('prefers the gallery over the legacy photoUrl when both exist', () => {
    expect(
      listingPhotoUrls({
        photoUrl: '/uploads/legacy.png',
        photos: [photo(1, '/uploads/a.png', 0)],
      }),
    ).toEqual(['/uploads/a.png']);
  });

  it('falls back to the legacy photoUrl when the gallery is empty', () => {
    expect(listingPhotoUrls({ photoUrl: '/uploads/legacy.png', photos: [] })).toEqual([
      '/uploads/legacy.png',
    ]);
  });

  it('is empty when the listing has no photo at all', () => {
    expect(listingPhotoUrls({ photoUrl: null, photos: [] })).toEqual([]);
  });
});
