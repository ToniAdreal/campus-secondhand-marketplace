import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import { useBlockUser, useMyBlocks, useUnblockUser, type BlockedUser } from './blocks';

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

const blocked: BlockedUser = {
  blockedId: 3,
  blockedUsername: 'seller',
  createdAt: '2026-10-18T00:00:00Z',
};

describe('blocks api hooks (#187)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('GETs /users/me/blocks and returns the blocked list from the envelope', async () => {
    getSpy.mockResolvedValueOnce(envelope([blocked]));

    const { result } = renderHook(() => useMyBlocks(), { wrapper: wrapperFor(makeClient()) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/users/me/blocks');
    expect(result.current.data).toEqual([blocked]);
  });

  it('does not fetch while disabled (anonymous)', async () => {
    const { result } = renderHook(() => useMyBlocks(false), {
      wrapper: wrapperFor(makeClient()),
    });
    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('POSTs /users/{id}/block on block', async () => {
    postSpy.mockResolvedValueOnce(envelope(null));
    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');
    const { result } = renderHook(() => useBlockUser(), { wrapper: wrapperFor(client) });

    result.current.mutate(3);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(postSpy).toHaveBeenCalledWith('/users/3/block');
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['blocks'] });
  });

  it('DELETEs /users/{id}/block on unblock', async () => {
    deleteSpy.mockResolvedValueOnce(envelope(null));
    const { result } = renderHook(() => useUnblockUser(), {
      wrapper: wrapperFor(makeClient()),
    });

    result.current.mutate(3);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(deleteSpy).toHaveBeenCalledWith('/users/3/block');
  });
});
