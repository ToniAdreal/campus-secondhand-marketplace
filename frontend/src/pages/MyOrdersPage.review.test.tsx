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
const postSpy = vi.spyOn(api, 'post');

const buyer: AuthUser = { id: 5, username: 'buyer', email: 'buyer@example.com', roles: ['USER'] };

const completedOrder: Order = {
  id: 12,
  itemId: 7,
  buyerId: 5,
  status: 'COMPLETED',
  amountCents: 12000,
  createdAt: '2026-10-06T17:00:00Z',
  captureId: 'cap_mock_bbbbbbbbbbbbbbbb',
  refundId: null,
};
const paidOrder: Order = { ...completedOrder, id: 13, status: 'PAID' };

function pageEnvelope(content: Order[]) {
  return {
    data: {
      code: 0,
      message: 'ok',
      data: { content, totalElements: content.length, totalPages: 1, number: 0 },
    },
  } as never;
}

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function renderAt() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/orders']}>
        <Routes>
          <Route path="/orders" element={<MyOrdersPage />} />
          <Route path="/orders/:id" element={<div>order detail stub</div>} />
          <Route path="/login" element={<div>login page stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('MyOrdersPage review control (backlog #175)', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
    useAuthStore.getState().login(buyer, 'tok');
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('shows the review entry only on COMPLETED rows', async () => {
    getSpy.mockResolvedValue(pageEnvelope([completedOrder, paidOrder]));

    renderAt();
    await waitFor(() => expect(screen.getByText('Order #13')).toBeTruthy());

    expect(screen.getAllByRole('button', { name: 'Leave a review' })).toHaveLength(1);
  });

  it('submits the chosen rating and text, then shows the submitted note', async () => {
    getSpy.mockResolvedValue(pageEnvelope([completedOrder]));
    postSpy.mockResolvedValue(
      envelope({
        id: 21,
        orderId: 12,
        buyerId: 5,
        reviewerUsername: 'buyer',
        sellerId: 3,
        rating: 4,
        body: 'Smooth trade',
        createdAt: '2026-10-06T18:00:00Z',
      }),
    );

    renderAt();
    await waitFor(() => expect(screen.getByText('Order #12')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Leave a review' }));
    fireEvent.change(screen.getByLabelText('Your rating'), { target: { value: '4' } });
    fireEvent.change(screen.getByLabelText(/Anything to add/), {
      target: { value: 'Smooth trade' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Submit review' }));

    await waitFor(() =>
      expect(screen.getByText(/Review submitted/)).toBeTruthy(),
    );
    expect(postSpy).toHaveBeenCalledWith('/orders/12/review', {
      rating: 4,
      body: 'Smooth trade',
    });
  });

  it('maps a duplicate-review 409 to plain copy', async () => {
    getSpy.mockResolvedValue(pageEnvelope([completedOrder]));
    postSpy.mockRejectedValue({
      response: { data: { code: 409, message: 'this order already has a review' } },
    });

    renderAt();
    await waitFor(() => expect(screen.getByText('Order #12')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Leave a review' }));
    fireEvent.click(screen.getByRole('button', { name: 'Submit review' }));

    await waitFor(() =>
      expect(screen.getByText('You have already reviewed this order.')).toBeTruthy(),
    );
  });
});
