import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import type { PriceOffer } from '../api/offers';
import { useAuthStore } from '../store/useAuthStore';
import OffersPage from './OffersPage';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');

const buyer: AuthUser = { id: 5, username: 'buyer', email: 'buyer@example.com', roles: ['USER'] };
const seller: AuthUser = { id: 3, username: 'seller', email: 'seller@example.com', roles: ['USER'] };

const madeOffer: PriceOffer = {
  id: 4,
  itemId: 7,
  buyerId: 5,
  sellerId: 3,
  priceCents: 8000,
  status: 'PENDING',
  orderId: null,
  expiresAt: '2026-10-12T08:00:00Z',
  createdAt: '2026-10-10T08:00:00Z',
};

const receivedOffer: PriceOffer = {
  id: 6,
  itemId: 9,
  buyerId: 8,
  sellerId: 3,
  priceCents: 5000,
  status: 'PENDING',
  orderId: null,
  expiresAt: '2026-10-12T08:00:00Z',
  createdAt: '2026-10-10T09:00:00Z',
};

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function pageOf(content: PriceOffer[]) {
  return { content, totalElements: content.length, totalPages: 1, number: 0 };
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/offers']}>
        <Routes>
          <Route path="/offers" element={<OffersPage />} />
          <Route path="/login" element={<div>login probe</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('OffersPage inbox', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
    getSpy.mockImplementation((url: string) => {
      if (url === '/offers/made') return Promise.resolve(envelope(pageOf([madeOffer])));
      if (url === '/offers/received') return Promise.resolve(envelope(pageOf([receivedOffer])));
      return Promise.resolve(envelope(pageOf([])));
    });
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('anonymous visitors bounce to login and no offer request fires', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('login probe')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('buyer section renders the made offer with a withdraw control that posts', async () => {
    useAuthStore.setState({ user: buyer });
    postSpy.mockResolvedValue(envelope({ ...madeOffer, status: 'WITHDRAWN' }));
    renderPage();

    await waitFor(() => expect(screen.getByText(/offered ¥80\.00/)).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Withdraw' }));
    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith('/offers/4/withdraw', {}),
    );
  });

  it('seller section renders a received offer with accept + decline controls', async () => {
    useAuthStore.setState({ user: seller });
    postSpy.mockResolvedValue(envelope({ ...receivedOffer, status: 'ACCEPTED', orderId: 11 }));
    renderPage();

    await waitFor(() => expect(screen.getByText(/offered ¥50\.00/)).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Accept' }));
    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith('/offers/6/accept', {}),
    );
  });

  it('empty sections say so plainly', async () => {
    useAuthStore.setState({ user: buyer });
    getSpy.mockImplementation(() => Promise.resolve(envelope(pageOf([]))));
    renderPage();

    await waitFor(() =>
      expect(screen.getByText(/You have not made any offers yet/)).toBeTruthy(),
    );
    expect(screen.getByText('No offers on your listings yet.')).toBeTruthy();
  });
});
