import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import type { Item } from '../api/items';
import { useAuthStore } from '../store/useAuthStore';
import ItemDetailPage from './ItemDetailPage';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');

const buyer: AuthUser = { id: 5, username: 'buyer', email: 'buyer@example.com', roles: ['USER'] };
const seller: AuthUser = { id: 3, username: 'seller', email: 'seller@example.com', roles: ['USER'] };

const fakeItem: Item = {
  id: 7,
  title: 'Used bike',
  description: 'Rides fine',
  priceCents: 12000,
  status: 'AVAILABLE',
  sellerId: 3,
  sellerUsername: 'seller',
  categoryId: null,
  categoryName: null,
  photoUrl: null,
  photos: [],
};

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function renderAt() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/items/7']}>
        <Routes>
          <Route path="/items/:id" element={<ItemDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ItemDetailPage make-an-offer (backlog #168)', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
    // The item query mounts before the message-thread query; the
    // one-shot item response fires first, the thread gets the default
    // empty page (same harness as ItemDetailPage.test.tsx).
    getSpy.mockImplementation(() =>
      Promise.resolve(envelope({ content: [], totalElements: 0, totalPages: 0, number: 0 })),
    );
    getSpy.mockResolvedValueOnce(envelope(fakeItem));
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('a signed-in non-seller submits an offer in cents and sees the sent note', async () => {
    useAuthStore.setState({ user: buyer });
    postSpy.mockResolvedValue(
      envelope({
        id: 4, itemId: 7, buyerId: 5, sellerId: 3, priceCents: 9000,
        status: 'PENDING', orderId: null,
        expiresAt: '2026-10-12T08:00:00Z', createdAt: '2026-10-10T08:00:00Z',
      }),
    );
    renderAt();

    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    fireEvent.change(screen.getByLabelText(/make an offer/i), { target: { value: '90' } });
    fireEvent.click(screen.getByRole('button', { name: 'Make an offer' }));

    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith('/items/7/offers', { priceCents: 9000 }),
    );
    await waitFor(() => expect(screen.getByText(/Offer sent/)).toBeTruthy());
  });

  it('the seller never sees the offer form on their own listing', async () => {
    useAuthStore.setState({ user: seller });
    renderAt();

    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    expect(screen.queryByLabelText(/make an offer/i)).toBeNull();
    expect(screen.queryByRole('button', { name: 'Make an offer' })).toBeNull();
  });

  it('an invalid amount is rejected client-side without a request', async () => {
    useAuthStore.setState({ user: buyer });
    renderAt();

    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Make an offer' }));
    await waitFor(() =>
      expect(screen.getByText('Please enter a price above ¥0.')).toBeTruthy(),
    );
    expect(postSpy).not.toHaveBeenCalledWith('/items/7/offers', expect.anything());
  });
});
