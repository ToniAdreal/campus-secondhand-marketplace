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

const pendingOrder: Order = {
  id: 9,
  itemId: 7,
  buyerId: 5,
  status: 'PENDING',
  amountCents: 12000,
  createdAt: '2026-10-06T17:00:00Z',
  captureId: null,
  refundId: null,
};
const paidOrder: Order = {
  ...pendingOrder,
  id: 10,
  status: 'PAID',
  captureId: 'cap_mock_aaaaaaaaaaaaaaaa',
};
const disputedOrder: Order = { ...paidOrder, id: 11, status: 'DISPUTED' };

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

const fakeDispute = {
  id: 3,
  orderId: 11,
  buyerId: 5,
  sellerId: 3,
  reason: 'the parcel never arrived',
  status: 'OPEN',
  previousStatus: 'PAID',
  listingRestored: null,
  createdAt: '2026-10-14T08:00:00Z',
  resolvedAt: null,
};

/**
 * Routes GETs by URL (backlog #200): a DISPUTED row also resolves its
 * dispute by order and reads its evidence list — both get honest
 * shapes, everything else gets the orders page.
 */
function mockGets(page: ReturnType<typeof pageEnvelope>) {
  getSpy.mockImplementation(((url: string) => {
    if (url.endsWith('/dispute')) {
      return Promise.resolve(envelope(fakeDispute));
    }
    if (url.includes('/evidence')) {
      return Promise.resolve(envelope([]));
    }
    return Promise.resolve(page);
  }) as never);
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

describe('MyOrdersPage dispute control (backlog #169)', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
    useAuthStore.getState().login(buyer, 'tok');
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('shows the dispute entry only on PAID/COMPLETED rows and the frozen note on DISPUTED rows', async () => {
    mockGets(pageEnvelope([pendingOrder, paidOrder, disputedOrder]));

    renderAt();
    await waitFor(() => expect(screen.getByText('Order #11')).toBeTruthy());

    // Exactly one "Report a problem" — the PAID row; PENDING cannot be
    // disputed and the DISPUTED row shows its frozen state instead.
    expect(screen.getAllByRole('button', { name: 'Report a problem' })).toHaveLength(1);
    expect(screen.getByText('DISPUTED')).toBeTruthy();
    expect(
      screen.getByText('Dispute open — this order is frozen while an admin reviews it.'),
    ).toBeTruthy();
  });

  it('submitting the reason form posts the dispute and confirms', async () => {
    mockGets(pageEnvelope([paidOrder]));
    postSpy.mockResolvedValue(
      envelope({
        id: 3,
        orderId: 10,
        buyerId: 5,
        sellerId: 3,
        reason: 'the parcel never arrived',
        status: 'OPEN',
        previousStatus: 'PAID',
        listingRestored: null,
        createdAt: '2026-10-14T08:00:00Z',
        resolvedAt: null,
      }),
    );

    renderAt();
    await waitFor(() => expect(screen.getByText('Order #10')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Report a problem' }));
    fireEvent.change(screen.getByLabelText('What went wrong?'), {
      target: { value: 'the parcel never arrived' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Open dispute' }));

    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith('/orders/10/dispute', {
        reason: 'the parcel never arrived',
      }),
    );
    await waitFor(() =>
      expect(
        screen.getByText('Dispute opened — an admin will review it. The order is frozen meanwhile.'),
      ).toBeTruthy(),
    );
  });
});
