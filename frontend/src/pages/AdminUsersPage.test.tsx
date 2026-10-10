import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import type { AdminUser } from '../api/admin';
import { useAuthStore } from '../store/useAuthStore';
import AdminUsersPage from './AdminUsersPage';

const getSpy = vi.spyOn(api, 'get');
const postSpy = vi.spyOn(api, 'post');

const adminCaller: AuthUser = {
  id: 1,
  username: 'adminboss',
  email: 'admin@example.com',
  roles: ['ADMIN', 'USER'],
};

const selfRow: AdminUser = {
  id: 1,
  username: 'adminboss',
  email: 'admin@example.com',
  roles: ['ADMIN', 'USER'],
  disabled: false,
  createdAt: '2026-09-01T10:00:00Z',
};
const otherAdminRow: AdminUser = {
  id: 2,
  username: 'secondadmin',
  email: 'second@example.com',
  roles: ['ADMIN'],
  disabled: false,
  createdAt: '2026-09-02T10:00:00Z',
};
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

function pageEnvelope(content: AdminUser[], number: number, totalPages: number) {
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
          <Route path="/admin/users" element={<AdminUsersPage />} />
          <Route path="/" element={<div>home page stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminUsersPage', () => {
  beforeEach(() => {
    // synchronous reset — store logout() now calls the server (would pollute spies)
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
    vi.useRealTimers();
  });

  it('renders the user table (username/email/roles/status/created) from GET /admin/users', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([selfRow, aliceRow, bobRow], 0, 1));

    renderAt('/admin/users');
    await waitFor(() => expect(screen.getByText('alice')).toBeTruthy());

    expect(screen.getByText('alice@example.com')).toBeTruthy();
    expect(screen.getByText('bob')).toBeTruthy();
    // status column: the disabled row reads Disabled, the active rows Active
    expect(screen.getByText('Disabled')).toBeTruthy();
    expect(screen.getAllByText('Active')).toHaveLength(2);
    expect(getSpy).toHaveBeenCalledWith('/admin/users', { params: { page: 0, size: 20 } });
  });

  it('non-admin sessions bounce home and fire no request', async () => {
    useAuthStore.getState().login(
      { id: 9, username: 'plainuser', email: 'plain@example.com', roles: ['USER'] },
      'tok',
    );

    renderAt('/admin/users');
    await waitFor(() => expect(screen.getByText('home page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('anonymous sessions bounce home and fire no request', async () => {
    renderAt('/admin/users');
    await waitFor(() => expect(screen.getByText('home page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('a 403 from the API renders the honest not-authorized state, never a fake list', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockRejectedValue(axiosFailure(403, 'Access Denied'));

    renderAt('/admin/users');
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toContain('not authorized');
    expect(screen.queryByText('alice')).toBeNull();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('search typing issues the q param (debounced, URL-synced) and resets to page 0', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockImplementation(((_path: string, config?: { params?: { q?: string } }) => {
      if (config?.params?.q === 'alice') {
        return Promise.resolve(pageEnvelope([aliceRow], 0, 1));
      }
      return Promise.resolve(pageEnvelope([selfRow, aliceRow, bobRow], 0, 1));
    }) as never);

    renderAt('/admin/users');
    await waitFor(() => expect(screen.getByText('bob')).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/search users/i), {
      target: { value: 'alice' },
    });
    // the debounced URL commit (300 ms, the browse page's pattern) issues
    // a fresh list request carrying the q param, starting from page 0
    await waitFor(
      () =>
        expect(getSpy).toHaveBeenCalledWith('/admin/users', {
          params: { page: 0, size: 20, q: 'alice' },
        }),
      { timeout: 3000 },
    );

    await waitFor(() => expect(screen.queryByText('bob')).toBeNull());
    expect(screen.getByText('alice')).toBeTruthy();
  });

  it('pagination walks pages with Previous/Next and stops at the last page', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockImplementation(((_path: string, config?: { params?: { page?: number } }) => {
      const page = config?.params?.page ?? 0;
      return Promise.resolve(
        page === 0 ? pageEnvelope([aliceRow], 0, 2) : pageEnvelope([bobRow], 1, 2),
      );
    }) as never);

    renderAt('/admin/users');
    await waitFor(() => expect(screen.getByText('alice')).toBeTruthy());
    expect(screen.getByText('Page 1 of 2')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Previous' }) as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    await waitFor(() => expect(screen.getByText('bob')).toBeTruthy());
    expect(getSpy).toHaveBeenCalledWith('/admin/users', { params: { page: 1, size: 20 } });
    expect(screen.getByText('Page 2 of 2')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Next' }) as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: 'Previous' }));
    await waitFor(() => expect(screen.getByText('alice')).toBeTruthy());
    expect(screen.getByText('Page 1 of 2')).toBeTruthy();
  });

  it('self and other-ADMIN rows render no Disable button; a disabled row renders Enable', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([selfRow, otherAdminRow, aliceRow, bobRow], 0, 1));

    renderAt('/admin/users');
    await waitFor(() => expect(screen.getByText('secondadmin')).toBeTruthy());

    // exactly one Disable button (alice) — self and the other ADMIN have none
    expect(screen.getAllByRole('button', { name: 'Disable' })).toHaveLength(1);
    // exactly one Enable button (the disabled bob row)
    expect(screen.getAllByRole('button', { name: 'Enable' })).toHaveLength(1);
  });

  it('Disable confirms first, posts to /admin/users/{id}/disable, and the list refetches', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([aliceRow], 0, 1));
    postSpy.mockResolvedValue({
      data: { code: 0, message: 'ok', data: { ...aliceRow, disabled: true } },
    } as never);
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);

    renderAt('/admin/users');
    await waitFor(() => expect(screen.getByText('alice')).toBeTruthy());
    const callsBefore = getSpy.mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: 'Disable' }));

    expect(confirmSpy).toHaveBeenCalled();
    await waitFor(() => expect(postSpy).toHaveBeenCalledWith('/admin/users/3/disable', {}));
    // success invalidates the users subtree — the list refetches
    await waitFor(() => expect(getSpy.mock.calls.length).toBeGreaterThan(callsBefore));
    confirmSpy.mockRestore();
  });

  it('declining the disable confirmation fires no request', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([aliceRow], 0, 1));
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);

    renderAt('/admin/users');
    await waitFor(() => expect(screen.getByText('alice')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Disable' }));

    expect(confirmSpy).toHaveBeenCalled();
    expect(postSpy).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it('Enable posts to /admin/users/{id}/enable and the list refetches', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([bobRow], 0, 1));
    postSpy.mockResolvedValue({
      data: { code: 0, message: 'ok', data: { ...bobRow, disabled: false } },
    } as never);

    renderAt('/admin/users');
    await waitFor(() => expect(screen.getByText('bob')).toBeTruthy());
    const callsBefore = getSpy.mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: 'Enable' }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledWith('/admin/users/4/enable', {}));
    await waitFor(() => expect(getSpy.mock.calls.length).toBeGreaterThan(callsBefore));
  });

  it('a 403 from disable maps to the friendly admin-guard copy', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([aliceRow], 0, 1));
    postSpy.mockRejectedValue(axiosFailure(403, 'cannot disable an admin user'));
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);

    renderAt('/admin/users');
    await waitFor(() => expect(screen.getByText('alice')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Disable' }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toBe(
      'Admin accounts cannot be disabled here — they are managed out-of-band.',
    );
    confirmSpy.mockRestore();
  });
});
