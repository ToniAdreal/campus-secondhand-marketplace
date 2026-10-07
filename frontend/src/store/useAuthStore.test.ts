import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, getAccessToken, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import { queryClient } from '../queryClient';
import { useAuthStore } from './useAuthStore';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');

const fakeUser: AuthUser = { id: 5, username: 'toni', email: 'toni@example.com', roles: ['USER'] };

function okEnvelope() {
  return { data: { code: 0, message: 'ok', data: null } } as never;
}

function userEnvelope(user: AuthUser) {
  return { data: { code: 0, message: 'ok', data: user } } as never;
}

describe('useAuthStore logout', () => {
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

  it('posts to /api/auth/logout, then resets user + in-memory credential', async () => {
    useAuthStore.getState().login(fakeUser, 'tok-abc');

    await useAuthStore.getState().logout();

    expect(postSpy).toHaveBeenCalledWith('/auth/logout');
    expect(useAuthStore.getState().user).toBeNull();
    expect(getAccessToken()).toBeNull();
  });

  it('clears the whole TanStack query cache on logout — no other server state lingers', async () => {
    useAuthStore.getState().login(fakeUser, 'tok-abc');
    queryClient.setQueryData(['items'], [{ id: 1 }]);
    queryClient.setQueryData(['orders'], [{ id: 9 }]);
    expect(queryClient.getQueryCache().getAll()).toHaveLength(2);

    await useAuthStore.getState().logout();

    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
  });

  it('still clears local state and the cache when the server call fails', async () => {
    postSpy.mockRejectedValueOnce(new Error('network down'));
    useAuthStore.getState().login(fakeUser, 'tok-abc');
    queryClient.setQueryData(['items'], [{ id: 1 }]);

    // the failure propagates so callers can react, but nothing stays logged in
    await expect(useAuthStore.getState().logout()).rejects.toThrow('network down');

    expect(postSpy).toHaveBeenCalledWith('/auth/logout');
    expect(useAuthStore.getState().user).toBeNull();
    expect(getAccessToken()).toBeNull();
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
  });

  it('login still stores the user and keeps the credential in memory only', () => {
    useAuthStore.getState().login(fakeUser, 'tok-abc');

    expect(useAuthStore.getState().user).toEqual(fakeUser);
    expect(getAccessToken()).toBe('tok-abc');
    expect(localStorage.length).toBe(0);
  });
});

describe('useAuthStore hydrate', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  afterEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  it('restores the user from GET /auth/me and resolves it', async () => {
    getSpy.mockResolvedValueOnce(userEnvelope(fakeUser));

    const result = await useAuthStore.getState().hydrate();

    expect(getSpy).toHaveBeenCalledWith('/auth/me');
    expect(result).toEqual(fakeUser);
    expect(useAuthStore.getState().user).toEqual(fakeUser);
  });

  it('never throws when no session exists — user stays null and the credential is dropped', async () => {
    setAccessToken('stale-tok');
    getSpy.mockRejectedValueOnce(new Error('Request failed with status code 401'));

    const result = await useAuthStore.getState().hydrate();

    expect(result).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
    // the UI and the request layer agree on logged-out
    expect(getAccessToken()).toBeNull();
  });

  it('keeps the in-memory credential on success — the 401 interceptor owns token placement', async () => {
    setAccessToken('tok-abc');
    getSpy.mockResolvedValueOnce(userEnvelope(fakeUser));

    await useAuthStore.getState().hydrate();

    expect(getAccessToken()).toBe('tok-abc');
    expect(useAuthStore.getState().user).toEqual(fakeUser);
  });
});
