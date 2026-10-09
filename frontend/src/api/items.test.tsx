import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import { itemKeys, useItems } from './items';

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
