import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, setAccessToken } from './api/client';
import type { AuthUser } from './api/auth';
import { queryClient } from './queryClient';
import { useAuthStore } from './store/useAuthStore';

/**
 * Query-client defaults contract (backlog #292).
 *
 * src/queryClient.ts is the app's single TanStack Query client, and its
 * defaults set cache freshness app-wide: a silent regression here (a
 * staleTime or retry change) would change every query's behaviour at
 * once. Pin the shipped values exactly, plus the documented logout
 * contract that the auth store clears this client's whole cache so no
 * other user's server state lingers in memory.
 */

const postSpy = vi.spyOn(api, 'post');

const fakeUser: AuthUser = { id: 5, username: 'toni', email: 'toni@example.com', roles: ['USER'] };

function okEnvelope() {
  return { data: { code: 0, message: 'ok', data: null } } as never;
}

describe('queryClient defaults', () => {
  it('pins the query defaults exactly — staleTime 30s, retry once', () => {
    const defaults = queryClient.getDefaultOptions().queries;

    expect(defaults).toBeDefined();
    expect(defaults?.staleTime).toBe(30_000);
    expect(defaults?.retry).toBe(1);
  });

});

describe('queryClient logout cache-clear (via the auth store)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    postSpy.mockResolvedValue(okEnvelope());
    useAuthStore.setState({ user: null });
    setAccessToken(null);
    queryClient.clear();
  });

  afterEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ user: null });
    setAccessToken(null);
    queryClient.clear();
  });

  it('logout empties the whole cache — no other user data lingers', async () => {
    useAuthStore.getState().login(fakeUser, 'tok-abc');
    queryClient.setQueryData(['items'], [{ id: 1 }]);
    queryClient.setQueryData(['orders'], [{ id: 9 }]);
    expect(queryClient.getQueryCache().getAll()).toHaveLength(2);

    await useAuthStore.getState().logout();

    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
    expect(useAuthStore.getState().user).toBeNull();
  });
});
