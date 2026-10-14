import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import type { ListingReport } from '../api/reports';
import { useAuthStore } from '../store/useAuthStore';
import AdminReportsPage from './AdminReportsPage';

const getSpy = vi.spyOn(api, 'get');
const postSpy = vi.spyOn(api, 'post');

const adminCaller: AuthUser = {
  id: 1,
  username: 'adminboss',
  email: 'admin@example.com',
  roles: ['ADMIN', 'USER'],
};

const openReport: ListingReport = {
  id: 10,
  reporterId: 5,
  itemId: 7,
  reason: 'SCAM',
  note: 'asks for payment off-platform',
  status: 'OPEN',
  createdAt: '2026-09-03T10:00:00Z',
};
const dismissedReport: ListingReport = {
  id: 9,
  reporterId: 6,
  itemId: 8,
  reason: 'SPAM',
  note: null,
  status: 'DISMISSED',
  createdAt: '2026-09-02T10:00:00Z',
};

function pageEnvelope(content: ListingReport[], number: number, totalPages: number) {
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
          <Route path="/admin/reports" element={<AdminReportsPage />} />
          <Route path="/" element={<div>home page stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminReportsPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('renders report rows (reporter/listing/reason/status) from GET /admin/reports', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([openReport, dismissedReport], 0, 1));

    renderAt('/admin/reports');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'SCAM' })).toBeTruthy());

    expect(screen.getByText('User #5')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Listing #7' }).getAttribute('href')).toBe('/items/7');
    expect(screen.getByText('asks for payment off-platform')).toBeTruthy();
    expect(screen.getByRole('cell', { name: 'OPEN' })).toBeTruthy();
    expect(screen.getByRole('cell', { name: 'DISMISSED' })).toBeTruthy();
    expect(getSpy).toHaveBeenCalledWith('/admin/reports', {
      params: { page: 0, size: 20 },
    });
  });

  it('non-admin sessions bounce home and fire no request', async () => {
    useAuthStore.getState().login(
      { id: 9, username: 'plainuser', email: 'plain@example.com', roles: ['USER'] },
      'tok',
    );

    renderAt('/admin/reports');
    await waitFor(() => expect(screen.getByText('home page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('anonymous sessions bounce home and fire no request', async () => {
    renderAt('/admin/reports');
    await waitFor(() => expect(screen.getByText('home page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('a 403 from the API renders the honest not-authorized state, never a fake queue', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockRejectedValue(axiosFailure(403, 'Access Denied'));

    renderAt('/admin/reports');
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toContain('not authorized');
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('the status filter is URL-synced and sent to the API', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockImplementation(((_path: string, config?: { params?: { status?: string } }) => {
      if (config?.params?.status === 'OPEN') {
        return Promise.resolve(pageEnvelope([openReport], 0, 1));
      }
      return Promise.resolve(pageEnvelope([openReport, dismissedReport], 0, 1));
    }) as never);

    renderAt('/admin/reports');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'DISMISSED' })).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/filter by status/i), {
      target: { value: 'OPEN' },
    });

    await waitFor(() =>
      expect(getSpy).toHaveBeenCalledWith('/admin/reports', {
        params: { page: 0, size: 20, status: 'OPEN' },
      }),
    );
    await waitFor(() => expect(screen.queryByRole('cell', { name: 'DISMISSED' })).toBeNull());
  });

  it('an initial ?status= filter is sent to the API', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([dismissedReport], 0, 1));

    renderAt('/admin/reports?status=DISMISSED');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'DISMISSED' })).toBeTruthy());
    expect(getSpy).toHaveBeenCalledWith('/admin/reports', {
      params: { page: 0, size: 20, status: 'DISMISSED' },
    });
  });

  it('Dismiss posts to the dismiss endpoint; resolved rows render no buttons', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    let current: ListingReport[] = [openReport, dismissedReport];
    getSpy.mockImplementation(() => Promise.resolve(pageEnvelope(current, 0, 1)));
    postSpy.mockImplementation((_path: string) => {
      current = [{ ...openReport, status: 'DISMISSED' }, dismissedReport];
      return Promise.resolve(envelope({ ...openReport, status: 'DISMISSED' }));
    });

    renderAt('/admin/reports');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'SCAM' })).toBeTruthy());
    // Only the OPEN row carries action buttons.
    expect(screen.getAllByRole('button', { name: 'Dismiss' })).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: /action \(withdraw listing\)/i })).toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));

    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith('/admin/reports/10/dismiss', {}),
    );
    // Invalidation refetches: the row now reads DISMISSED and loses its buttons.
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Dismiss' })).toBeNull());
  });

  it('Action posts to the action endpoint (withdraw listing)', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([openReport], 0, 1));
    postSpy.mockResolvedValue(envelope({ ...openReport, status: 'ACTIONED' }));

    renderAt('/admin/reports');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'SCAM' })).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: /action \(withdraw listing\)/i }));

    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith('/admin/reports/10/action', {}),
    );
  });

  it('a 422 on a transition maps to friendly copy', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([openReport], 0, 1));
    postSpy.mockRejectedValue(axiosFailure(422, 'report already dismissed'));

    renderAt('/admin/reports');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'SCAM' })).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toBe(
      'That report has already been resolved the other way.',
    );
    expect(screen.queryByText(/report already dismissed/)).toBeNull();
  });

  it('an empty queue renders the no-reports state and states the no-auto-hide rule', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([], 0, 0));

    renderAt('/admin/reports');
    await waitFor(() => expect(screen.getByText('No reports yet.')).toBeTruthy());
    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.getByText(/no auto-hide/i)).toBeTruthy();
  });

  it('pagination walks pages with Previous/Next', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockImplementation(((_path: string, config?: { params?: { page?: number } }) => {
      const page = config?.params?.page ?? 0;
      return Promise.resolve(
        page === 0 ? pageEnvelope([openReport], 0, 2) : pageEnvelope([dismissedReport], 1, 2),
      );
    }) as never);

    renderAt('/admin/reports');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'SCAM' })).toBeTruthy());
    expect(screen.getByText('Page 1 of 2')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    await waitFor(() => expect(screen.getByRole('cell', { name: 'DISMISSED' })).toBeTruthy());
    expect(getSpy).toHaveBeenCalledWith('/admin/reports', { params: { page: 1, size: 20 } });
    expect(screen.getByText('Page 2 of 2')).toBeTruthy();
  });
});
