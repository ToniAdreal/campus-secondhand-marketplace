import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import type { AdminDashboard } from '../api/admin';
import { useAuthStore } from '../store/useAuthStore';
import AdminDashboardPage from './AdminDashboardPage';

const getSpy = vi.spyOn(api, 'get');

const adminCaller: AuthUser = {
  id: 1,
  username: 'adminboss',
  email: 'admin@example.com',
  roles: ['ADMIN', 'USER'],
};

const plainCaller: AuthUser = {
  id: 2,
  username: 'regularuser',
  email: 'user@example.com',
  roles: ['USER'],
};

// Mirrors the backend AdminDashboardTest seed mix: distinguishable
// counts per field so a swapped field cannot pass silently.
const dashboardFixture: AdminDashboard = {
  window: '24h',
  windowStart: '2026-10-18T00:00:00Z',
  usersRegistered: 5,
  listingsCreated: 12,
  listingsAvailableNow: 11,
  listingsExpiredInWindow: 2,
  ordersTotalInWindow: 6,
  ordersPending: 1,
  ordersPaid: 2,
  ordersCompleted: 3,
  ordersCancelled: 4,
  ordersRefunded: 7,
  ordersDisputed: 8,
  captures: 9,
  refunds: 10,
  openReports: 13,
  openDisputes: 14,
  webhooksMatched: 15,
  webhooksMismatched: 16,
};

function dashboardEnvelope(data: AdminDashboard) {
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
          <Route path="/admin/dashboard" element={<AdminDashboardPage />} />
          <Route path="/" element={<div>home page stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminDashboardPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('renders the windowed counts, per-status orders and snapshots', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(dashboardEnvelope(dashboardFixture));

    renderAt('/admin/dashboard');

    await waitFor(() => expect(screen.getByText('Users registered')).toBeTruthy());
    expect(getSpy).toHaveBeenCalledWith('/admin/dashboard', {
      params: { window: '24h' },
    });
    expect(screen.getByText('Listings created')).toBeTruthy();
    expect(screen.getByText('Listings expired')).toBeTruthy();
    expect(screen.getByText('Orders created')).toBeTruthy();
    expect(screen.getByText('Captures')).toBeTruthy();
    expect(screen.getByText('Webhook deliveries matched')).toBeTruthy();
    expect(screen.getByText('Webhook deliveries mismatched')).toBeTruthy();
    expect(screen.getByText('Disputed')).toBeTruthy();
    expect(screen.getByText('Listings available now')).toBeTruthy();
    expect(screen.getByText('Open reports')).toBeTruthy();
    expect(screen.getByText('Open disputes')).toBeTruthy();
    // A distinctive fixture value lands next to its label.
    expect(screen.getByText('13')).toBeTruthy();
    expect(screen.getByText('16')).toBeTruthy();
    // Honest labelling: declines are absent by design, and the copy says why.
    expect(screen.getByText(/Declined captures are not shown/)).toBeTruthy();
  });

  it('switching the window refetches with the new window param', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockImplementation(((_url: string, config?: { params?: { window?: string } }) => {
      const w = config?.params?.window ?? '24h';
      return Promise.resolve(
        dashboardEnvelope({ ...dashboardFixture, window: w, usersRegistered: w === '7d' ? 42 : 5 }),
      );
    }) as never);

    renderAt('/admin/dashboard');

    await waitFor(() => expect(screen.getByText('5')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Last 7 days' }));

    await waitFor(() => expect(screen.getByText('42')).toBeTruthy());
    expect(getSpy).toHaveBeenCalledWith('/admin/dashboard', {
      params: { window: '7d' },
    });
    expect(screen.getByRole('button', { name: 'Last 7 days' }).getAttribute('aria-pressed')).toBe(
      'true',
    );
  });

  it('bounces a non-admin session home and fires no request', async () => {
    useAuthStore.getState().login(plainCaller, 'tok');

    renderAt('/admin/dashboard');

    await waitFor(() => expect(screen.getByText('home page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('bounces an anonymous session home and fires no request', async () => {
    renderAt('/admin/dashboard');

    await waitFor(() => expect(screen.getByText('home page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('renders the not-authorized state on a 403 from the API', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockRejectedValue(axiosFailure(403, 'forbidden'));

    renderAt('/admin/dashboard');

    await waitFor(() =>
      expect(screen.getByText(/You are not authorized to view this page/)).toBeTruthy(),
    );
  });
});
