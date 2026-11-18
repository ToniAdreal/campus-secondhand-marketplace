import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import {
  sessionKeys,
  useLoginHistory,
  useRevokeOtherSessions,
  useRevokeSession,
  useRevokeTrustedDevice,
  useSessions,
  useTrustedDevices,
  type AuthSession,
  type LoginEvent,
  type TrustedDevice,
} from './auth';
import type { Page } from './items';

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

const currentSession: AuthSession = {
  id: 'family-current',
  userAgent: 'Mozilla/5.0 (X11; Linux x86_64) Chrome/126.0',
  ipAddress: '203.0.113.7',
  createdAt: '2026-10-08T01:00:00Z',
  lastActiveAt: '2026-10-09T01:30:00Z',
  current: true,
};

const otherSession: AuthSession = {
  id: 'family-other',
  userAgent: null,
  ipAddress: '198.51.100.23',
  createdAt: '2026-10-07T12:00:00Z',
  lastActiveAt: '2026-10-08T12:00:00Z',
  current: false,
};

const sessionList = [currentSession, otherSession];

const deviceA: TrustedDevice = {
  id: 7,
  userAgent: 'Mozilla/5.0 (X11; Linux x86_64) Chrome/126.0',
  ipAddress: '203.0.113.7',
  createdAt: '2026-10-09T01:00:00Z',
  lastUsedAt: null,
  expiresAt: '2026-11-08T01:00:00Z',
};

const deviceB: TrustedDevice = {
  id: 8,
  userAgent: null,
  ipAddress: null,
  createdAt: '2026-10-09T02:00:00Z',
  lastUsedAt: '2026-10-10T02:00:00Z',
  expiresAt: '2026-11-09T02:00:00Z',
};

const deviceList = [deviceA, deviceB];

const historyPage: Page<LoginEvent> = {
  content: [
    {
      id: 1,
      outcome: 'SUCCESS',
      identifier: 'toni',
      ipAddress: '203.0.113.7',
      userAgent: 'Mozilla/5.0 (X11; Linux x86_64) Chrome/126.0',
      createdAt: '2026-10-09T01:00:00Z',
    },
  ],
  totalElements: 1,
  totalPages: 1,
  number: 0,
};

describe('session hooks (backlog #395)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('useSessions() loads the session list with no argument (default enabled)', async () => {
    getSpy.mockResolvedValueOnce(envelope(sessionList));

    const { result } = renderHook(() => useSessions(), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/auth/sessions');
    expect(result.current.data).toEqual(sessionList);
  });

  it('useSessions(false) fires no request while disabled', async () => {
    const { result } = renderHook(() => useSessions(false), {
      wrapper: wrapperFor(makeClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('useLoginHistory() loads the first history page with no argument (default enabled)', async () => {
    getSpy.mockResolvedValueOnce(envelope(historyPage));

    const { result } = renderHook(() => useLoginHistory(), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/auth/login-history', {
      params: { page: 0, size: 20 },
    });
    expect(result.current.data).toEqual(historyPage);
  });

  it('useLoginHistory(false) fires no request while disabled', async () => {
    const { result } = renderHook(() => useLoginHistory(false), {
      wrapper: wrapperFor(makeClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('useTrustedDevices() loads the devices with no argument (default enabled)', async () => {
    getSpy.mockResolvedValueOnce(envelope(deviceList));

    const { result } = renderHook(() => useTrustedDevices(), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/auth/trusted-devices');
    expect(result.current.data).toEqual(deviceList);
  });

  it('useTrustedDevices(false) fires no request while disabled', async () => {
    const { result } = renderHook(() => useTrustedDevices(false), {
      wrapper: wrapperFor(makeClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('useRevokeSession drops the revoked row from the cached list and invalidates the sessions subtree', async () => {
    const client = makeClient();
    client.setQueryData(sessionKeys.list(), sessionList);
    const setQueryDataSpy = vi.spyOn(client, 'setQueryData');
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');
    getSpy.mockResolvedValue(envelope(sessionList));
    deleteSpy.mockResolvedValueOnce(envelope(null));

    const { result } = renderHook(() => useRevokeSession(), {
      wrapper: wrapperFor(client),
    });
    await result.current.mutateAsync('family-other');

    expect(deleteSpy).toHaveBeenCalledWith('/auth/sessions/family-other');
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: sessionKeys.all });
    // The optimistic drop is the setQueryData updater itself: apply the
    // exact updater the hook registered to the seeded list and to
    // undefined, so the filter, the equality and the optional chain are
    // all asserted — not just the refetch the page tests already cover.
    expect(setQueryDataSpy).toHaveBeenCalledWith(
      sessionKeys.list(),
      expect.any(Function),
    );
    const updater = setQueryDataSpy.mock.calls
      .map((call) => call[1])
      .find((fn) => typeof fn === 'function') as unknown as (
      old: AuthSession[] | undefined,
    ) => AuthSession[] | undefined;
    expect(updater(sessionList)).toEqual([currentSession]);
    expect(updater(undefined)).toBeUndefined();
  });

  it('useRevokeTrustedDevice drops the revoked device from the cached list and invalidates the sessions subtree', async () => {
    const client = makeClient();
    client.setQueryData(sessionKeys.trustedDevices(), deviceList);
    const setQueryDataSpy = vi.spyOn(client, 'setQueryData');
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');
    getSpy.mockResolvedValue(envelope(deviceList));
    deleteSpy.mockResolvedValueOnce(envelope(null));

    const { result } = renderHook(() => useRevokeTrustedDevice(), {
      wrapper: wrapperFor(client),
    });
    await result.current.mutateAsync(7);

    expect(deleteSpy).toHaveBeenCalledWith('/auth/trusted-devices/7');
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: sessionKeys.all });
    expect(setQueryDataSpy).toHaveBeenCalledWith(
      sessionKeys.trustedDevices(),
      expect.any(Function),
    );
    const updater = setQueryDataSpy.mock.calls
      .map((call) => call[1])
      .find((fn) => typeof fn === 'function') as unknown as (
      old: TrustedDevice[] | undefined,
    ) => TrustedDevice[] | undefined;
    expect(updater(deviceList)).toEqual([deviceB]);
    expect(updater(undefined)).toBeUndefined();
  });

  it('useRevokeOtherSessions reports the revoked count and invalidates the sessions subtree', async () => {
    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');
    postSpy.mockResolvedValueOnce(envelope({ revokedCount: 1 }));

    const { result } = renderHook(() => useRevokeOtherSessions(), {
      wrapper: wrapperFor(client),
    });
    const count = await result.current.mutateAsync();

    expect(postSpy).toHaveBeenCalledWith('/auth/sessions/revoke-others');
    expect(count).toBe(1);
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: sessionKeys.all });
  });
});
