import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import {
  useFollowSeller,
  useMyFollows,
  useUnfollowSeller,
  type FollowedSeller,
} from './follows';

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

const followed: FollowedSeller = {
  sellerId: 3,
  sellerUsername: 'campus-seller',
  createdAt: '2026-10-23T00:00:00Z',
};

describe('follows api hooks (#217)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('GETs /users/me/follows and returns the following list from the envelope', async () => {
    getSpy.mockResolvedValueOnce(envelope([followed]));

    const { result } = renderHook(() => useMyFollows(), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/users/me/follows');
    expect(result.current.data).toEqual([followed]);
  });

  it('does not fetch while disabled (anonymous)', async () => {
    const { result } = renderHook(() => useMyFollows(false), {
      wrapper: wrapperFor(makeClient()),
    });
    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('POSTs /users/me/follows/{sellerId} on follow and invalidates the follows subtree', async () => {
    postSpy.mockResolvedValueOnce(envelope(null));
    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useFollowSeller(), {
      wrapper: wrapperFor(client),
    });
    result.current.mutate(3);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(postSpy).toHaveBeenCalledWith('/users/me/follows/3');
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['follows'] });
  });

  it('DELETEs /users/me/follows/{sellerId} on unfollow and invalidates the follows subtree', async () => {
    deleteSpy.mockResolvedValueOnce(envelope(null));
    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useUnfollowSeller(), {
      wrapper: wrapperFor(client),
    });
    result.current.mutate(3);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(deleteSpy).toHaveBeenCalledWith('/users/me/follows/3');
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['follows'] });
  });
});
