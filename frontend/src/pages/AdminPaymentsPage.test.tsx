import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import type { SettlementSummary } from '../api/admin';
import { useAuthStore } from '../store/useAuthStore';
import AdminPaymentsPage from './AdminPaymentsPage';

const getSpy = vi.spyOn(api, 'get');

const adminCaller: AuthUser = {
  id: 1,
  username: 'adminboss',
  email: 'admin@example.com',
  roles: ['ADMIN', 'USER'],
};

// Mirrors the backend AdminSettlementTest seed mix: PAID 10000 +
// COMPLETED 20000 + REFUNDED 5000 captured, PENDING/CANCELLED contribute
// nothing to the money totals.
const summaryFixture: SettlementSummary = {
  capturedCents: 35000,
  refundedCents: 5000,
  netCents: 30000,
  totalOrders: 5,
  pendingCount: 1,
  paidCount: 1,
  completedCount: 1,
  cancelledCount: 1,
  refundedCount: 1,
};

function summaryEnvelope(data: SettlementSummary) {
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
          <Route path="/admin/payments" element={<AdminPaymentsPage />} />
          <Route path="/" element={<div>home page stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminPaymentsPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('renders captured / refunded / net totals formatted from cents, plus per-status counts', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(summaryEnvelope(summaryFixture));

    renderAt('/admin/payments');

    await waitFor(() => expect(screen.getByText('¥350.00')).toBeTruthy());
    expect(screen.getByText('¥50.00')).toBeTruthy();
    expect(screen.getByText('¥300.00')).toBeTruthy();
    expect(getSpy).toHaveBeenCalledWith('/admin/payments/settlement');
    // Per-status counts: total + one row per status from the fixture.
    expect(screen.getByText('Total orders')).toBeTruthy();
    expect(screen.getByText('Pending')).toBeTruthy();
    expect(screen.getByText('Paid')).toBeTruthy();
    expect(screen.getByText('Completed')).toBeTruthy();
    expect(screen.getByText('Cancelled')).toBeTruthy();
    // 'Refunded' labels both a totals card and a status row.
    expect(screen.getAllByText('Refunded')).toHaveLength(2);
    // Honest labelling: the figures are mock-PSP derived totals.
    expect(screen.getByText(/Mock-PSP settlement summary/)).toBeTruthy();
    expect(screen.getByText(/no real money has moved/i)).toBeTruthy();
  });

  it('renders an all-zero summary for an empty database instead of an error', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(
      summaryEnvelope({
        capturedCents: 0,
        refundedCents: 0,
        netCents: 0,
        totalOrders: 0,
        pendingCount: 0,
        paidCount: 0,
        completedCount: 0,
        cancelledCount: 0,
        refundedCount: 0,
      }),
    );

    renderAt('/admin/payments');

    await waitFor(() => expect(screen.getAllByText('¥0.00')).toHaveLength(3));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('non-admin sessions bounce home and fire no request', async () => {
    useAuthStore.getState().login(
      { id: 9, username: 'plainuser', email: 'plain@example.com', roles: ['USER'] },
      'tok',
    );

    renderAt('/admin/payments');
    await waitFor(() => expect(screen.getByText('home page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('anonymous sessions bounce home and fire no request', async () => {
    renderAt('/admin/payments');
    await waitFor(() => expect(screen.getByText('home page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('a 403 from the API renders the honest not-authorized state, never fake totals', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockRejectedValue(axiosFailure(403, 'Access Denied'));

    renderAt('/admin/payments');
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toContain('not authorized');
    expect(screen.queryByText('¥350.00')).toBeNull();
  });

  it('an API error renders the envelope message', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockRejectedValue(axiosFailure(500, 'settlement unavailable'));

    renderAt('/admin/payments');
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toContain('settlement unavailable');
  });
});
