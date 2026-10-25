import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import {
  describeAdminCategoryError,
  useAdminCategories,
  useCreateCategory,
  useDeleteCategory,
  useRenameCategory,
  useCategories,
  type Category,
} from './categories';

const getSpy = vi.spyOn(api, 'get');
const postSpy = vi.spyOn(api, 'post');
const patchSpy = vi.spyOn(api, 'patch');
const deleteSpy = vi.spyOn(api, 'delete');

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

describe('ADMIN category writes (backlog #229)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('create POSTs the name only when the slug is blank, so the backend derives it', async () => {
    postSpy.mockResolvedValueOnce(envelope({ id: 4, name: 'Books', slug: 'books' }));
    const client = makeClient();
    const { result } = renderHook(() => useCreateCategory(), { wrapper: wrapperFor(client) });

    result.current.mutate({ name: 'Books', slug: '  ' });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(postSpy).toHaveBeenCalledWith('/categories', { name: 'Books' });
  });

  it('create POSTs an explicit slug when one is given', async () => {
    postSpy.mockResolvedValueOnce(envelope({ id: 4, name: 'Books', slug: 'used-books' }));
    const { result } = renderHook(() => useCreateCategory(), { wrapper: wrapperFor(makeClient()) });

    result.current.mutate({ name: 'Books', slug: 'used-books' });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(postSpy).toHaveBeenCalledWith('/categories', { name: 'Books', slug: 'used-books' });
  });

  it('rename PATCHes without a slug when the slug is blank, so the backend keeps it', async () => {
    patchSpy.mockResolvedValueOnce(envelope({ id: 1, name: 'Textbooks', slug: 'textbooks' }));
    const { result } = renderHook(() => useRenameCategory(), { wrapper: wrapperFor(makeClient()) });

    result.current.mutate({ id: 1, name: 'Textbooks', slug: '' });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(patchSpy).toHaveBeenCalledWith('/categories/1', { name: 'Textbooks' });
  });

  it('delete DELETEs the category by id', async () => {
    deleteSpy.mockResolvedValueOnce(undefined);
    const { result } = renderHook(() => useDeleteCategory(), { wrapper: wrapperFor(makeClient()) });

    result.current.mutate(2);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(deleteSpy).toHaveBeenCalledWith('/categories/2');
  });

  it('a successful write invalidates the shared taxonomy key (the #87 evict fires)', async () => {
    // The admin list and the dropdowns both hang off ['categories'], so
    // one invalidation refetches every consumer — a stale admin-only
    // cache cannot survive behind the dropdowns. Asserted as the refetch
    // itself: an invalidation that refetches immediately clears the
    // isInvalidated flag again, so the flag alone would read false.
    const client = makeClient();
    getSpy.mockResolvedValue(envelope(taxonomy));
    postSpy.mockResolvedValueOnce(envelope({ id: 4, name: 'Books', slug: 'books' }));

    const { result } = renderHook(
      () => ({ list: useAdminCategories(true), create: useCreateCategory() }),
      { wrapper: wrapperFor(client) },
    );
    await waitFor(() => expect(result.current.list.isSuccess).toBe(true));
    const callsBefore = getSpy.mock.calls.length;

    result.current.create.mutate({ name: 'Books' });
    await waitFor(() => expect(result.current.create.isSuccess).toBe(true));
    await waitFor(() => expect(getSpy.mock.calls.length).toBeGreaterThan(callsBefore));
  });

  it('the admin query fires no request when disabled (non-admin page bounce)', async () => {
    const { result } = renderHook(() => useAdminCategories(false), {
      wrapper: wrapperFor(makeClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('a delete 409 maps to the recategorize-first copy, a save 409 to the duplicate copy', () => {
    const conflict = {
      isAxiosError: true,
      response: { status: 409, data: { code: 409, message: 'category in use', data: null } },
    };
    expect(describeAdminCategoryError(conflict, 'delete')).toContain('Recategorize those listings first');
    expect(describeAdminCategoryError(conflict, 'save')).toContain('already exists');
  });
});
