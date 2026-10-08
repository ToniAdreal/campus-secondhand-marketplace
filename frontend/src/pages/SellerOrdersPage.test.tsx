import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import { type Order } from '../api/orders';
import { useAuthStore } from '../store/useAuthStore';
import SellerOrdersPage from './SellerOrdersPage';

const getSpy = vi.spyOn(api, 'get');

const seller: AuthUser = { id: 2, username: 'seller', email: 'seller@example.com', roles: ['USER'] };

const order1: Order = {
  id: 9,
  itemId: 7,
  buyerId: 5,
  status: 'PENDING',
  amountCents: 12000,
  createdAt: '2026-10-06T17:00:00Z',
};

const order2: Order = { ...order1, id: 10, buyerId: 8, status: 'PAID', amountCents: 4500 };

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
          <Route path="/seller/orders" element={<SellerOrdersPage />} />
          <Route path="/items/:id" element={<div>item detail stub</div>} />
          <Route path="/login" element={<div>login page stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SellerOrdersPage', () => {
  beforeEach(() => {
    // synchronous reset — store logout() now calls the server (would pollute spies)
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
  });

  it('renders orders on the seller\'s listings with status chips and listing links', async () => {
    useAuthStore.getState().login(seller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([order1, order2], 0, 1));

    renderAt('/seller/orders');
    await waitFor(() => expect(screen.getByText('Order #9')).toBeTruthy());

    // both orders render with their status chips (the shared chip component)
    expect(screen.getByText('Order #9')).toBeTruthy();
    expect(screen.getByText('PENDING')).toBeTruthy();
    expect(screen.getByText('Order #10')).toBeTruthy();
    expect(screen.getByText('PAID')).toBeTruthy();

    // the seller view shows the buyer identity and the amount
    expect(screen.getByText('Buyer #5')).toBeTruthy();
    expect(screen.getByText('¥120.00')).toBeTruthy();

    // each row links to the listing, not the order detail page
    const link = screen.getByText('Order #9').closest('a');
    expect(link?.getAttribute('href')).toBe('/items/7');

    // reads the seller endpoint, newest first, single page
    expect(getSpy).toHaveBeenCalledWith('/seller/orders', { params: { page: 0, size: 20 } });
  });

  it('anonymous visitors bounce to /login and fire no request', async () => {
    renderAt('/seller/orders');
    await waitFor(() => expect(screen.getByText('login page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('a 401 mid-session redirects to /login', async () => {
    // credential died after login (e.g. expired + no refresh cookie left)
    useAuthStore.getState().login(seller, 'tok');
    getSpy.mockRejectedValue(axiosFailure(401, 'invalid or expired credential'));

    renderAt('/seller/orders');
    await waitFor(() => expect(screen.getByText('login page stub')).toBeTruthy());
  });

  it('shows the empty-state copy when nothing was ordered on the listings', async () => {
    useAuthStore.getState().login(seller, 'tok');
    getSpy.mockResolvedValue(pageEnvelope([], 0, 1));

    renderAt('/seller/orders');
    await waitFor(() => expect(screen.getByText('No orders on your listings yet.')).toBeTruthy());
    expect(screen.getByText('Sell an item').getAttribute('href')).toBe('/items/new');
    expect(screen.queryByText('Load more')).toBeNull();
  });

  it('"Load more" stitches older pages; the button disappears on the last page', async () => {
    useAuthStore.getState().login(seller, 'tok');
    // two pages: page 0 newest, page 1 older; totalPages 2
    getSpy
      .mockResolvedValueOnce(pageEnvelope([order1], 0, 2))
      .mockResolvedValue(pageEnvelope([order2], 1, 2));

    renderAt('/seller/orders');
    await waitFor(() => expect(screen.getByText('Order #9')).toBeTruthy());
    expect(screen.queryByText('Order #10')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));

    // both pages stitched in order — newest page stays on top
    await waitFor(() => expect(screen.getByText('Order #10')).toBeTruthy());
    expect(getSpy).toHaveBeenCalledWith('/seller/orders', { params: { page: 0, size: 20 } });
    expect(getSpy).toHaveBeenCalledWith('/seller/orders', { params: { page: 1, size: 20 } });

    // last page reached -> no more button
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
  });

  it('a non-401 list failure shows the friendly error mapping', async () => {
    useAuthStore.getState().login(seller, 'tok');
    getSpy.mockRejectedValue(axiosFailure(500, 'upstream exploded'));

    renderAt('/seller/orders');
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toBe('upstream exploded');
  });
});
