import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import { type Order } from '../api/orders';
import { useAuthStore } from '../store/useAuthStore';
import MyOrdersPage from './MyOrdersPage';

const getSpy = vi.spyOn(api, 'get');

const buyer: AuthUser = { id: 5, username: 'buyer', email: 'buyer@example.com', roles: ['USER'] };

const order1: Order = {
  id: 9,
  itemId: 7,
  buyerId: 5,
  status: 'PENDING',
  amountCents: 12000,
  createdAt: '2026-10-06T17:00:00Z',
  captureId: null,
  refundId: null,
};

const order2: Order = {
  ...order1,
  id: 10,
  status: 'PAID',
  amountCents: 4500,
  captureId: 'cap_mock_aaaaaaaaaaaaaaaa',
};

const order3: Order = {
  ...order1,
  id: 11,
  status: 'REFUNDED',
  amountCents: 999,
  captureId: 'cap_mock_bbbbbbbbbbbbbbbb',
  refundId: 'rfd_mock_bbbbbbbbbbbbbbbb',
};

function pageEnvelope(content: Order[], number: number, totalPages: number) {
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
          <Route path="/orders" element={<MyOrdersPage />} />
          <Route path="/orders/:id" element={<div>order detail stub</div>} />
          <Route path="/login" element={<div>login page stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('MyOrdersPage', () => {
  beforeEach(() => {
    // synchronous reset — store logout() now calls the server (would pollute spies)
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
  });

  it('renders the buyer\'s orders with status chips and detail links', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([order1, order2], 0, 1));

    renderAt('/orders');
    // the heading renders while loading; wait for the data to land
    await waitFor(() => expect(screen.getByText('Order #9')).toBeTruthy());

    // both orders render with their status chips
    expect(screen.getByText('Order #9')).toBeTruthy();
    expect(screen.getByText('PENDING')).toBeTruthy();
    expect(screen.getByText('Order #10')).toBeTruthy();
    expect(screen.getByText('PAID')).toBeTruthy();

    // each row links to its detail page
    const link = screen.getByText('Order #9').closest('a');
    expect(link?.getAttribute('href')).toBe('/orders/9');

    // newest-first read from the server, single page requested
    expect(getSpy).toHaveBeenCalledWith('/orders', { params: { page: 0, size: 20 } });
  });

  it('rows render mock PSP references only when non-null (backlog #119)', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([order1, order2, order3], 0, 1));

    renderAt('/orders');
    await waitFor(() => expect(screen.getByText('Order #11')).toBeTruthy());

    // PAID row: capture reference only; REFUNDED row: both references;
    // PENDING row (order #9, both ids null): neither line
    expect(screen.getByText('Payment reference (demo): cap_mock_aaaaaaaaaaaaaaaa')).toBeTruthy();
    expect(screen.getByText('Payment reference (demo): cap_mock_bbbbbbbbbbbbbbbb')).toBeTruthy();
    expect(screen.getByText('Refund reference (demo): rfd_mock_bbbbbbbbbbbbbbbb')).toBeTruthy();
    expect(screen.getAllByText(/Payment reference \(demo\):/)).toHaveLength(2);
    expect(screen.getAllByText(/Refund reference \(demo\):/)).toHaveLength(1);
  });

  it('anonymous visitors bounce to /login', async () => {
    renderAt('/orders');
    await waitFor(() => expect(screen.getByText('login page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('a 401 mid-session redirects to /login', async () => {
    // credential died after login (e.g. expired + no refresh cookie left)
    useAuthStore.getState().login(buyer, 'tok');
    getSpy.mockRejectedValue(axiosFailure(401, 'invalid or expired credential'));

    renderAt('/orders');
    await waitFor(() => expect(screen.getByText('login page stub')).toBeTruthy());
  });

  it('shows the empty-state copy when the buyer has no orders', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([], 0, 1));

    renderAt('/orders');
    await waitFor(() => expect(screen.getByText(/haven't bought anything yet/)).toBeTruthy());
    expect(screen.getByText('Browse listings').getAttribute('href')).toBe('/');
    expect(screen.queryByText('Load more')).toBeNull();
  });

  it('"Load more" stitches older pages; the button disappears on the last page', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    // two pages: page 0 newest, page 1 older; totalPages 2
    getSpy
      .mockResolvedValueOnce(pageEnvelope([order1], 0, 2))
      .mockResolvedValue(pageEnvelope([order2, order3], 1, 2));

    renderAt('/orders');
    await waitFor(() => expect(screen.getByText('Order #9')).toBeTruthy());
    expect(screen.queryByText('Order #10')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));

    // both pages stitched in order — newest page stays on top
    await waitFor(() => expect(screen.getByText('Order #10')).toBeTruthy());
    expect(screen.getByText('Order #11')).toBeTruthy();
    expect(screen.getByText('REFUNDED')).toBeTruthy();
    const items = screen.getAllByRole('link', { name: /Order #/ });
    expect(items.map((el) => el.textContent)).toEqual([
      'Order #9PENDING',
      'Order #10PAID',
      'Order #11REFUNDED',
    ]);

    // last page reached -> no more button
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
    expect(getSpy).toHaveBeenCalledWith('/orders', { params: { page: 0, size: 20 } });
    expect(getSpy).toHaveBeenCalledWith('/orders', { params: { page: 1, size: 20 } });
  });

  it('a non-401 list failure shows the friendly error mapping', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    getSpy.mockRejectedValue(axiosFailure(500, 'upstream exploded'));

    renderAt('/orders');
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toBe('upstream exploded');
  });
});
