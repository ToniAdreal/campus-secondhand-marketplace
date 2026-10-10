import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import type { Page } from './items';
import {
  AUDIT_ACTIONS,
  AUDIT_TARGET_TYPES,
  adminAuditLogKeys,
  adminUserKeys,
  describeAdminUserError,
  describeAuditLogError,
  isForbidden,
  useAdminAuditLog,
  useAdminUsers,
  useDisableAdminUser,
  useEnableAdminUser,
  type AdminUser,
  type AuditLogEntry,
} from './admin';

const getSpy = vi.spyOn(api, 'get');
const postSpy = vi.spyOn(api, 'post');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function envelopeError(code: number, message: string) {
  return { response: { data: { code, message } } };
}

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function wrapperFor(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

const aliceRow: AdminUser = {
  id: 3,
  username: 'alice',
  email: 'alice@example.com',
  roles: ['USER'],
  disabled: false,
  createdAt: '2026-09-03T10:00:00Z',
};
const bobRow: AdminUser = {
  id: 4,
  username: 'bob',
  email: 'bob@example.com',
  roles: ['USER'],
  disabled: true,
  createdAt: '2026-09-04T10:00:00Z',
};
const usersPage: Page<AdminUser> = {
  content: [aliceRow, bobRow],
  totalElements: 2,
  totalPages: 1,
  number: 0,
};

const auditRow: AuditLogEntry = {
  id: 11,
  actorUserId: 1,
  action: 'USER_DISABLED',
  targetType: 'USER',
  targetId: 3,
  createdAt: '2026-09-05T10:00:00Z',
};
const auditPage: Page<AuditLogEntry> = {
  content: [auditRow],
  totalElements: 1,
  totalPages: 1,
  number: 0,
};

describe('admin query keys', () => {
  it('users keys hang off [admin, users] and carry page + q as passed', () => {
    expect(adminUserKeys.all).toEqual(['admin', 'users']);
    expect(adminUserKeys.list(2, 'alice')).toEqual(['admin', 'users', 'list', 2, 'alice']);
    expect(adminUserKeys.list(0, '')).toEqual(['admin', 'users', 'list', 0, '']);
  });

  it('audit-log keys are a sibling subtree and normalise unset filters', () => {
    expect(adminAuditLogKeys.all).toEqual(['admin', 'audit-log']);
    // The two subtrees must never collide: invalidating one must not
    // refetch the other.
    expect(adminAuditLogKeys.all).not.toEqual(adminUserKeys.all);
    expect(adminAuditLogKeys.list(1, {})).toEqual([
      'admin',
      'audit-log',
      'list',
      1,
      null,
      '',
      '',
      null,
    ]);
    expect(
      adminAuditLogKeys.list(0, {
        actorUserId: 7,
        action: 'ORDER_PAID',
        targetType: 'ORDER',
        targetId: 42,
      }),
    ).toEqual(['admin', 'audit-log', 'list', 0, 7, 'ORDER_PAID', 'ORDER', 42]);
    // null and undefined filters normalise to the same key, so a page
    // that clears a filter hits the cache entry of a page that never set it.
    expect(adminAuditLogKeys.list(0, { actorUserId: null, targetId: null })).toEqual(
      adminAuditLogKeys.list(0, {}),
    );
  });

  it('pins the allowlisted audit actions and target types to the backend enums', () => {
    expect(AUDIT_ACTIONS).toEqual([
      'PASSWORD_CHANGED',
      'PASSWORD_RESET_COMPLETED',
      'TOTP_ENABLED',
      'TOTP_DISABLED',
      'RECOVERY_CODES_REGENERATED',
      'ORDER_PAID',
      'ORDER_CANCELLED',
      'ORDER_COMPLETED',
      'ORDER_REFUNDED',
      'USER_DISABLED',
      'USER_ENABLED',
    ]);
    expect(AUDIT_TARGET_TYPES).toEqual(['USER', 'ORDER']);
  });
});

describe('useAdminUsers', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('GETs /admin/users with page/size, omits a blank q, and unwraps the page envelope', async () => {
    getSpy.mockResolvedValueOnce(envelope(usersPage));

    const { result } = renderHook(() => useAdminUsers(), { wrapper: wrapperFor(makeClient()) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledTimes(1);
    expect(getSpy).toHaveBeenCalledWith('/admin/users', { params: { page: 0, size: 20 } });
    expect(result.current.data).toEqual(usersPage);
  });

  it('omits a whitespace-only q as well (it is blank after trimming)', async () => {
    getSpy.mockResolvedValueOnce(envelope(usersPage));

    const { result } = renderHook(() => useAdminUsers(0, '   '), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/admin/users', { params: { page: 0, size: 20 } });
  });

  it('sends page/size through and trims a non-blank q', async () => {
    getSpy.mockResolvedValueOnce(envelope({ ...usersPage, number: 2 }));

    const { result } = renderHook(() => useAdminUsers(2, '  Alice  ', true, 5), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/admin/users', {
      params: { page: 2, size: 5, q: 'Alice' },
    });
    expect(result.current.data?.number).toBe(2);
  });

  it('stays idle when disabled (anonymous / non-admin page guard)', async () => {
    const { result } = renderHook(() => useAdminUsers(0, '', false), {
      wrapper: wrapperFor(makeClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('propagates the failure shape so the page can render its error state', async () => {
    const failure = {
      isAxiosError: true,
      response: { status: 403, data: { code: 403, message: 'forbidden', data: null } },
    };
    getSpy.mockRejectedValueOnce(failure);

    const { result } = renderHook(() => useAdminUsers(), { wrapper: wrapperFor(makeClient()) });
    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(result.current.error).toBe(failure);
    expect(result.current.data).toBeUndefined();
  });
});

describe('admin user mutations', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('useDisableAdminUser posts an empty body to /disable, unwraps, and invalidates the users prefix', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    postSpy.mockResolvedValueOnce(envelope({ ...aliceRow, disabled: true }));

    const { result } = renderHook(() => useDisableAdminUser(), {
      wrapper: wrapperFor(queryClient),
    });
    const returned = await result.current.mutateAsync(3);

    expect(postSpy).toHaveBeenCalledWith('/admin/users/3/disable', {});
    expect(returned).toEqual({ ...aliceRow, disabled: true });
    // Prefix invalidation of the whole subtree, exactly once — every
    // paginated/searched list variant refetches, the audit log does not.
    expect(invalidateSpy).toHaveBeenCalledTimes(1);
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: adminUserKeys.all });
    expect(invalidateSpy).not.toHaveBeenCalledWith({ queryKey: adminAuditLogKeys.all });
  });

  it('useEnableAdminUser posts an empty body to /enable, unwraps, and invalidates the users prefix', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    postSpy.mockResolvedValueOnce(envelope(bobRow));

    const { result } = renderHook(() => useEnableAdminUser(), {
      wrapper: wrapperFor(queryClient),
    });
    const returned = await result.current.mutateAsync(4);

    expect(postSpy).toHaveBeenCalledWith('/admin/users/4/enable', {});
    expect(returned).toEqual(bobRow);
    expect(invalidateSpy).toHaveBeenCalledTimes(1);
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: adminUserKeys.all });
  });

  it('a failed disable invalidates nothing', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    postSpy.mockRejectedValueOnce(new Error('network down'));

    const { result } = renderHook(() => useDisableAdminUser(), {
      wrapper: wrapperFor(queryClient),
    });
    await expect(result.current.mutateAsync(3)).rejects.toThrow('network down');

    expect(invalidateSpy).not.toHaveBeenCalled();
  });
});

describe('useAdminAuditLog', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('GETs /admin/audit-log with page/size only when no filters are set, and unwraps the page', async () => {
    getSpy.mockResolvedValueOnce(envelope(auditPage));

    const { result } = renderHook(() => useAdminAuditLog(), { wrapper: wrapperFor(makeClient()) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledTimes(1);
    expect(getSpy).toHaveBeenCalledWith('/admin/audit-log', { params: { page: 0, size: 20 } });
    expect(result.current.data).toEqual(auditPage);
  });

  it('sends every filter that is set, alongside page/size', async () => {
    getSpy.mockResolvedValueOnce(envelope(auditPage));

    const { result } = renderHook(
      () =>
        useAdminAuditLog(
          1,
          { actorUserId: 7, action: 'ORDER_PAID', targetType: 'ORDER', targetId: 42 },
          true,
          10,
        ),
      { wrapper: wrapperFor(makeClient()) },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/admin/audit-log', {
      params: {
        page: 1,
        size: 10,
        actorUserId: 7,
        action: 'ORDER_PAID',
        targetType: 'ORDER',
        targetId: 42,
      },
    });
  });

  it('omits null ids and empty action/targetType filters entirely', async () => {
    getSpy.mockResolvedValueOnce(envelope(auditPage));

    const { result } = renderHook(
      () => useAdminAuditLog(0, { actorUserId: null, action: '', targetType: '', targetId: null }),
      { wrapper: wrapperFor(makeClient()) },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/admin/audit-log', { params: { page: 0, size: 20 } });
  });

  it('sends only the filters that are set (partial filter set)', async () => {
    getSpy.mockResolvedValueOnce(envelope(auditPage));

    const { result } = renderHook(() => useAdminAuditLog(0, { action: 'USER_DISABLED' }), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/admin/audit-log', {
      params: { page: 0, size: 20, action: 'USER_DISABLED' },
    });
  });

  it('stays idle when disabled (anonymous / non-admin page guard)', async () => {
    const { result } = renderHook(() => useAdminAuditLog(0, {}, false), {
      wrapper: wrapperFor(makeClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });
});

describe('describeAdminUserError', () => {
  it.each([
    ['self-disable guard', envelopeError(403, 'you cannot disable yourself'), 'You cannot disable your own account.'],
    ['admin guard', envelopeError(403, 'cannot disable an admin user'), 'Admin accounts cannot be disabled here — they are managed out-of-band.'],
    ['generic forbidden', envelopeError(403, 'forbidden'), 'You are not authorized to do that.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in again to manage users.'],
    ['unknown user', envelopeError(404, 'not found'), 'That user no longer exists.'],
    ['unknown error keeps the server message', envelopeError(500, 'database unavailable'), 'database unavailable'],
    ['no envelope at all', new Error('boom'), 'Something went wrong. Please try again.'],
    ['empty object', {}, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeAdminUserError(error)).toBe(expected);
  });

  it('the yourself guard wins when a 403 message mentions both yourself and admin', () => {
    // describeAdminUserError checks /yourself/ before /admin/ — pin the
    // order so a future reorder cannot silently change the copy a user
    // disabling their own ADMIN account sees.
    expect(describeAdminUserError(envelopeError(403, 'an admin cannot disable yourself'))).toBe(
      'You cannot disable your own account.',
    );
  });
});

describe('describeAuditLogError', () => {
  it.each([
    ['forbidden', envelopeError(403, 'forbidden'), 'You are not authorized to view the audit log.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in again to view the audit log.'],
    ['invalid filter', envelopeError(400, 'unknown action'), 'That filter is not valid.'],
    ['unknown error keeps the server message', envelopeError(500, 'database unavailable'), 'database unavailable'],
    ['no envelope at all', new Error('boom'), 'Something went wrong. Please try again.'],
    ['empty object', {}, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeAuditLogError(error)).toBe(expected);
  });
});

describe('isForbidden', () => {
  it.each([
    ['axios 403', { isAxiosError: true, response: { status: 403 } }, true],
    ['axios 401', { isAxiosError: true, response: { status: 401 } }, false],
    ['axios 404', { isAxiosError: true, response: { status: 404 } }, false],
    ['axios error without a response', { isAxiosError: true }, false],
    ['plain error', new Error('boom'), false],
    ['null', null, false],
    ['undefined', undefined, false],
  ])('%s', (_label, error, expected) => {
    expect(isForbidden(error)).toBe(expected);
  });
});
