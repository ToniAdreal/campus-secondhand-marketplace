import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import type { AuditLogEntry } from '../api/admin';
import { useAuthStore } from '../store/useAuthStore';
import AdminAuditLogPage from './AdminAuditLogPage';

const getSpy = vi.spyOn(api, 'get');

const adminCaller: AuthUser = {
  id: 1,
  username: 'adminboss',
  email: 'admin@example.com',
  roles: ['ADMIN', 'USER'],
};

const paidRow: AuditLogEntry = {
  id: 10,
  actorUserId: 3,
  action: 'ORDER_COMPLETED',
  targetType: 'ORDER',
  targetId: 42,
  createdAt: '2026-09-03T10:00:00Z',
};
const disabledRow: AuditLogEntry = {
  id: 9,
  actorUserId: 1,
  action: 'USER_DISABLED',
  targetType: 'USER',
  targetId: 4,
  createdAt: '2026-09-02T10:00:00Z',
};

function pageEnvelope(content: AuditLogEntry[], number: number, totalPages: number) {
  return {
    data: {
      code: 0,
      message: 'ok',
      data: { content, totalElements: content.length, totalPages, number },
    },
  } as never;
}

function axiosFailure(status: number, message: string) {
  return {
    isAxiosError: true,
    response: { status, data: { code: status, message, data: null } },
  };
}

function renderAt(initialEntry: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/admin/audit-log" element={<AdminAuditLogPage />} />
          <Route path="/" element={<div>home page stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminAuditLogPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    vi.useRealTimers();
  });

  it('renders audit rows (actor/action/target/time) from GET /admin/audit-log', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([paidRow, disabledRow], 0, 1));

    renderAt('/admin/audit-log');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'ORDER_COMPLETED' })).toBeTruthy());

    expect(screen.getByText('User #3')).toBeTruthy();
    expect(screen.getByText('ORDER #42')).toBeTruthy();
    expect(screen.getByRole('cell', { name: 'USER_DISABLED' })).toBeTruthy();
    expect(screen.getByText('USER #4')).toBeTruthy();
    expect(getSpy).toHaveBeenCalledWith('/admin/audit-log', {
      params: { page: 0, size: 20 },
    });
  });

  it('non-admin sessions bounce home and fire no request', async () => {
    useAuthStore.getState().login(
      { id: 9, username: 'plainuser', email: 'plain@example.com', roles: ['USER'] },
      'tok',
    );

    renderAt('/admin/audit-log');
    await waitFor(() => expect(screen.getByText('home page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('anonymous sessions bounce home and fire no request', async () => {
    renderAt('/admin/audit-log');
    await waitFor(() => expect(screen.getByText('home page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('a 403 from the API renders the honest not-authorized state, never a fake trail', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockRejectedValue(axiosFailure(403, 'Access Denied'));

    renderAt('/admin/audit-log');
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toContain('not authorized');
    expect(screen.queryByRole('cell', { name: 'ORDER_COMPLETED' })).toBeNull();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('initial URL filters are sent to the API', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([paidRow], 0, 1));

    renderAt('/admin/audit-log?action=ORDER_COMPLETED&targetType=ORDER&targetId=42');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'ORDER_COMPLETED' })).toBeTruthy());
    expect(getSpy).toHaveBeenCalledWith('/admin/audit-log', {
      params: { page: 0, size: 20, action: 'ORDER_COMPLETED', targetType: 'ORDER', targetId: 42 },
    });
  });

  it('changing the action filter refetches with the allowlisted action param', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockImplementation(((_path: string, config?: { params?: { action?: string } }) => {
      if (config?.params?.action === 'USER_DISABLED') {
        return Promise.resolve(pageEnvelope([disabledRow], 0, 1));
      }
      return Promise.resolve(pageEnvelope([paidRow, disabledRow], 0, 1));
    }) as never);

    renderAt('/admin/audit-log');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'ORDER_COMPLETED' })).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/filter by action/i), {
      target: { value: 'USER_DISABLED' },
    });

    await waitFor(() =>
      expect(getSpy).toHaveBeenCalledWith('/admin/audit-log', {
        params: { page: 0, size: 20, action: 'USER_DISABLED' },
      }),
    );
    await waitFor(() => expect(screen.queryByRole('cell', { name: 'ORDER_COMPLETED' })).toBeNull());
    expect(screen.getByRole('cell', { name: 'USER_DISABLED' })).toBeTruthy();
  });

  it('changing the target-type filter refetches with the targetType param', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([paidRow], 0, 1));

    renderAt('/admin/audit-log');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'ORDER_COMPLETED' })).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/filter by target type/i), {
      target: { value: 'ORDER' },
    });

    await waitFor(() =>
      expect(getSpy).toHaveBeenCalledWith('/admin/audit-log', {
        params: { page: 0, size: 20, targetType: 'ORDER' },
      }),
    );
  });

  it('typing an actor id issues the actorUserId param (debounced, URL-synced)', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockImplementation(((_path: string, config?: { params?: { actorUserId?: number } }) => {
      if (config?.params?.actorUserId === 3) {
        return Promise.resolve(pageEnvelope([paidRow], 0, 1));
      }
      return Promise.resolve(pageEnvelope([paidRow, disabledRow], 0, 1));
    }) as never);

    renderAt('/admin/audit-log');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'USER_DISABLED' })).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/filter by actor user id/i), {
      target: { value: '3' },
    });

    await waitFor(
      () =>
        expect(getSpy).toHaveBeenCalledWith('/admin/audit-log', {
          params: { page: 0, size: 20, actorUserId: 3 },
        }),
      { timeout: 3000 },
    );
    await waitFor(() => expect(screen.queryByRole('cell', { name: 'USER_DISABLED' })).toBeNull());
  });

  it('pagination walks pages with Previous/Next and stops at the last page', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockImplementation(((_path: string, config?: { params?: { page?: number } }) => {
      const page = config?.params?.page ?? 0;
      return Promise.resolve(
        page === 0 ? pageEnvelope([paidRow], 0, 2) : pageEnvelope([disabledRow], 1, 2),
      );
    }) as never);

    renderAt('/admin/audit-log');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'ORDER_COMPLETED' })).toBeTruthy());
    expect(screen.getByText('Page 1 of 2')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Previous' }) as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    await waitFor(() => expect(screen.getByRole('cell', { name: 'USER_DISABLED' })).toBeTruthy());
    expect(getSpy).toHaveBeenCalledWith('/admin/audit-log', { params: { page: 1, size: 20 } });
    expect(screen.getByText('Page 2 of 2')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Next' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('an empty filtered page renders the no-matches state', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([], 0, 0));

    renderAt('/admin/audit-log?action=TOTP_ENABLED');
    await waitFor(() =>
      expect(screen.getByText('No audit entries match these filters.')).toBeTruthy(),
    );
    expect(screen.queryByRole('table')).toBeNull();
  });
});
