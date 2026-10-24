import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import type { SellerPayout, SellerPayoutTotals } from '../api/payouts';
import { useAuthStore } from '../store/useAuthStore';
import SellerPayoutsPage from './SellerPayoutsPage';

const getSpy = vi.spyOn(api, 'get');

const seller: AuthUser = { id: 2, username: 'seller', email: 'seller@example.com', roles: ['USER'] };

const payout1: SellerPayout = {
  id: 1,
  orderId: 9,
  sellerId: 2,
  amountCents: 10000,
  adjustmentsCents: 0,
  netCents: 10000,
  status: 'PENDING',
  createdAt: '2026-10-20T10:00:00Z',
  payableAt: null,
  paidAt: null,
};

const payout2: SellerPayout = {
  id: 2,
  orderId: 10,
  sellerId: 2,
  amountCents: 10000,
  adjustmentsCents: -10000,
  netCents: 0,
  status: 'PENDING',
  createdAt: '2026-10-21T10:00:00Z',
  payableAt: null,
  paidAt: null,
};

const totals: SellerPayoutTotals = {
  pendingCount: 2,
  pendingCents: 20000,
  payableCount: 0,
  payableCents: 0,
  paidCount: 0,
  paidCents: 0,
  adjustmentsCents: -10000,
  netCents: 10000,
};

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function pageEnvelope(content: SellerPayout[], number: number, totalPages: number) {
  return envelope({ content, totalElements: content.length, totalPages, number });
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
          <Route path="/seller/payouts" element={<SellerPayoutsPage />} />
          <Route path="/seller/orders" element={<div>sales page stub</div>} />
          <Route path="/login" element={<div>login page stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SellerPayoutsPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('renders payout rows with settlement, adjustments and net plus the totals header', async () => {
    useAuthStore.getState().login(seller, 'tok');
    getSpy.mockImplementation((url: string) => {
      if (url === '/seller/payouts/totals') {
        return Promise.resolve(envelope(totals));
      }
      return Promise.resolve(pageEnvelope([payout1, payout2], 0, 1));
    });

    renderAt('/seller/payouts');
    await waitFor(() => expect(screen.getByText('Payout for order #9')).toBeTruthy());

    expect(screen.getByText('Payout for order #10')).toBeTruthy();
    // settlement + adjustment + net on the refunded row
    expect(screen.getByText('Adjustments ¥-100.00')).toBeTruthy();
    expect(screen.getAllByText('Net ¥0.00').length).toBeGreaterThan(0);
    // totals header: net owed reconciles with the rows
    expect(screen.getByText(/Net owed: ¥100.00/)).toBeTruthy();
    expect(screen.getByText('Adjustments (refunds after completion): ¥-100.00')).toBeTruthy();
    // the honest-scope note is on the page
    expect(screen.getByText(/The mock pays nobody/)).toBeTruthy();

    expect(getSpy).toHaveBeenCalledWith('/seller/payouts', { params: { page: 0, size: 20 } });
    expect(getSpy).toHaveBeenCalledWith('/seller/payouts/totals');
  });

  it('anonymous visitors bounce to /login and fire no request', async () => {
    renderAt('/seller/payouts');
    await waitFor(() => expect(screen.getByText('login page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('a 401 mid-session redirects to /login', async () => {
    useAuthStore.getState().login(seller, 'tok');
    getSpy.mockRejectedValue(axiosFailure(401, 'invalid or expired credential'));

    renderAt('/seller/payouts');
    await waitFor(() => expect(screen.getByText('login page stub')).toBeTruthy());
  });

  it('empty payouts render the empty state', async () => {
    useAuthStore.getState().login(seller, 'tok');
    getSpy.mockImplementation((url: string) => {
      if (url === '/seller/payouts/totals') {
        return Promise.resolve(
          envelope({
            pendingCount: 0,
            pendingCents: 0,
            payableCount: 0,
            payableCents: 0,
            paidCount: 0,
            paidCents: 0,
            adjustmentsCents: 0,
            netCents: 0,
          }),
        );
      }
      return Promise.resolve(pageEnvelope([], 0, 1));
    });

    renderAt('/seller/payouts');
    await waitFor(() => expect(screen.getByText(/No payouts yet/)).toBeTruthy());
  });
});
