import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import type { ReportAppeal } from '../api/appeals';
import { useAuthStore } from '../store/useAuthStore';
import AdminAppealsPage from './AdminAppealsPage';

const getSpy = vi.spyOn(api, 'get');
const postSpy = vi.spyOn(api, 'post');

const adminCaller: AuthUser = {
  id: 1,
  username: 'adminboss',
  email: 'admin@example.com',
  roles: ['ADMIN', 'USER'],
};

const openAppeal: ReportAppeal = {
  id: 10,
  reportId: 4,
  itemId: 7,
  sellerId: 3,
  reason: 'this is my own textbook, sold once',
  status: 'OPEN',
  listingRestored: null,
  createdAt: '2026-10-18T10:00:00Z',
  resolvedAt: null,
};
const upheldAppeal: ReportAppeal = {
  id: 8,
  reportId: 2,
  itemId: 5,
  sellerId: 3,
  reason: 'listing was misreported',
  status: 'UPHELD',
  listingRestored: true,
  createdAt: '2026-10-16T10:00:00Z',
  resolvedAt: '2026-10-17T10:00:00Z',
};

function pageEnvelope(content: ReportAppeal[], number: number, totalPages: number) {
  return {
    data: {
      code: 0,
      message: 'ok',
      data: { content, totalElements: content.length, totalPages, number },
    },
  } as never;
}

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
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
          <Route path="/admin/appeals" element={<AdminAppealsPage />} />
          <Route path="/" element={<div>home page stub</div>} />
          <Route path="/items/:id" element={<div>item detail stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminAppealsPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('renders appeal rows from GET /admin/appeals with resolution state', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([openAppeal, upheldAppeal], 0, 1));

    renderAt('/admin/appeals');
    await waitFor(() =>
      expect(screen.getByText('this is my own textbook, sold once')).toBeTruthy(),
    );

    expect(screen.getByRole('link', { name: 'Listing #7' }).getAttribute('href')).toBe(
      '/items/7',
    );
    expect(screen.getByText('Report #4')).toBeTruthy();
    // Both fixture rows belong to seller #3.
    expect(screen.getAllByText('User #3')).toHaveLength(2);
    expect(screen.getByRole('cell', { name: 'OPEN' })).toBeTruthy();
    expect(screen.getByRole('cell', { name: 'UPHELD' })).toBeTruthy();
    // The upheld row records that its listing was restored.
    expect(screen.getByText('Restored to AVAILABLE')).toBeTruthy();
    // Resolution buttons render only on the OPEN row.
    expect(screen.getAllByRole('button', { name: 'Uphold appeal' })).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: 'Reject appeal' })).toHaveLength(1);
    expect(getSpy).toHaveBeenCalledWith('/admin/appeals', {
      params: { page: 0, size: 20 },
    });
  });

  it('non-admin sessions bounce home and fire no request', async () => {
    useAuthStore.getState().login(
      { id: 9, username: 'plainuser', email: 'plain@example.com', roles: ['USER'] },
      'tok',
    );

    renderAt('/admin/appeals');
    await waitFor(() => expect(screen.getByText('home page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('a 403 from the API renders the honest not-authorized state, never a fake queue', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockRejectedValue(axiosFailure(403, 'Access Denied'));

    renderAt('/admin/appeals');
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toContain('not authorized');
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('Uphold posts to the uphold endpoint; Reject posts to the reject endpoint', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([openAppeal], 0, 1));
    postSpy.mockResolvedValue(
      envelope({ ...openAppeal, status: 'UPHELD', listingRestored: true }),
    );

    renderAt('/admin/appeals');
    await waitFor(() =>
      expect(screen.getByText('this is my own textbook, sold once')).toBeTruthy(),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Uphold appeal' }));
    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith('/admin/appeals/10/uphold', {}),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Reject appeal' }));
    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith('/admin/appeals/10/reject', {}),
    );
  });

  it('a 422 on a resolution maps to friendly copy', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([openAppeal], 0, 1));
    postSpy.mockRejectedValue(axiosFailure(422, 'appeal already rejected'));

    renderAt('/admin/appeals');
    await waitFor(() =>
      expect(screen.getByText('this is my own textbook, sold once')).toBeTruthy(),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Uphold appeal' }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toBe(
      'That appeal has already been resolved the other way.',
    );
  });

  it('the status filter is URL-synced and sent to the API', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockImplementation(((_path: string, config?: { params?: { status?: string } }) => {
      if (config?.params?.status === 'UPHELD') {
        return Promise.resolve(pageEnvelope([upheldAppeal], 0, 1));
      }
      return Promise.resolve(pageEnvelope([openAppeal, upheldAppeal], 0, 1));
    }) as never);

    renderAt('/admin/appeals');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'OPEN' })).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/filter by status/i), {
      target: { value: 'UPHELD' },
    });

    await waitFor(() =>
      expect(getSpy).toHaveBeenCalledWith('/admin/appeals', {
        params: { page: 0, size: 20, status: 'UPHELD' },
      }),
    );
    await waitFor(() => expect(screen.queryByRole('cell', { name: 'OPEN' })).toBeNull());
  });

  it('empty queue renders the empty state', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([], 0, 0));

    renderAt('/admin/appeals');
    await waitFor(() => expect(screen.getByText('No appeals yet.')).toBeTruthy());
    expect(screen.queryByRole('table')).toBeNull();
  });
});
