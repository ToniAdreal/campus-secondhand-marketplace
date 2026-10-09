import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import { useCategories, type Category } from './categories';

const getSpy = vi.spyOn(api, 'get');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

// The client is created in the test body and captured by wrapperFor,
// the pattern this suite uses for hook tests (see messages.test.tsx).
function wrapperFor(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
}

const taxonomy: Category[] = [
  { id: 1, name: 'Textbooks', slug: 'textbooks' },
  { id: 2, name: 'Electronics', slug: 'electronics' },
  { id: 3, name: 'Furniture', slug: 'furniture' },
];

describe('useCategories', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('GETs /categories and returns the taxonomy from the envelope, in order', async () => {
    getSpy.mockResolvedValueOnce(envelope(taxonomy));

    const { result } = renderHook(() => useCategories(), { wrapper: wrapperFor(makeClient()) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledTimes(1);
    expect(getSpy).toHaveBeenCalledWith('/categories');
    // Insertion order is the contract the dropdowns rely on — the hook
    // must pass the list through untouched, never re-sort it.
    expect(result.current.data).toEqual(taxonomy);
    expect(result.current.data?.map((c) => c.slug)).toEqual([
      'textbooks',
      'electronics',
      'furniture',
    ]);
  });

  it('propagates the failure shape so pages can render their error state', async () => {
    const failure = {
      isAxiosError: true,
      response: { status: 401, data: { code: 401, message: 'unauthorized', data: null } },
    };
    getSpy.mockRejectedValueOnce(failure);

    const { result } = renderHook(() => useCategories(), { wrapper: wrapperFor(makeClient()) });
    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(result.current.error).toBe(failure);
    expect(result.current.data).toBeUndefined();
  });

  it('an empty taxonomy is a successful empty list, not an error', async () => {
    getSpy.mockResolvedValueOnce(envelope([]));

    const { result } = renderHook(() => useCategories(), { wrapper: wrapperFor(makeClient()) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toEqual([]);
  });
});
