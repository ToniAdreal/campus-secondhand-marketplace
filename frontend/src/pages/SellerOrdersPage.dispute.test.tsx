import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import { type Order, type SellerSalesSummary } from '../api/orders';
import { useAuthStore } from '../store/useAuthStore';
import SellerOrdersPage from './SellerOrdersPage';

const getSpy = vi.spyOn(api, 'get');
const postSpy = vi.spyOn(api, 'post');

const seller: AuthUser = { id: 2, username: 'seller', email: 'seller@example.com', roles: ['USER'] };

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
  captureId: 'cap_mock_cccccccccccccccc',
};
const shippedOrder: Order = {
  ...paidOrder,
  id: 12,
  status: 'SHIPPED',
  carrier: 'SF Express',
  trackingNumber: 'SF1234567890',
};
const completedOrder: Order = { ...paidOrder, id: 13, status: 'COMPLETED' };
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

const summaryEnvelope = (overrides: Partial<SellerSalesSummary> = {}) =>
  envelope({
    totalOrders: 0,
    completedCount: 0,
    grossCents: 0,
    currentMonth: { totalOrders: 0, completedCount: 0, grossCents: 0 },
    ...overrides,
  });

const fakeDispute = {
  id: 3,
  orderId: 11,
  buyerId: 5,
  sellerId: 2,
  reason: 'the buyer never confirmed receipt',
  status: 'OPEN',
  previousStatus: 'PAID',
  listingRestored: null,
  createdAt: '2026-10-14T08:00:00Z',
  resolvedAt: null,
};

/**
 * Routes GETs by URL: the seller list and summary get their own
 * envelopes, a DISPUTED row resolves its dispute by order and reads
 * its (empty) evidence list.
 */
function mockGets(page: ReturnType<typeof pageEnvelope>) {
  getSpy.mockImplementation(((url: string) => {
    if (url === '/seller/orders/summary') {
      return Promise.resolve(summaryEnvelope());
    }
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
      <MemoryRouter initialEntries={['/seller/orders']}>
        <Routes>
          <Route path="/seller/orders" element={<SellerOrdersPage />} />
          <Route path="/items/:id" element={<div>item detail stub</div>} />
          <Route path="/login" element={<div>login page stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SellerOrdersPage dispute control (backlog #414)', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
    useAuthStore.getState().login(seller, 'tok');
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('shows the dispute entry only on PAID/SHIPPED/COMPLETED rows and the frozen note on DISPUTED rows', async () => {
    mockGets(pageEnvelope([pendingOrder, paidOrder, shippedOrder, completedOrder, disputedOrder]));

    renderAt();
    await waitFor(() => expect(screen.getByText('Order #11')).toBeTruthy());

    // Three "Report a problem" buttons — PAID, SHIPPED and COMPLETED;
    // PENDING cannot be disputed and the DISPUTED row shows its frozen
    // state instead.
    expect(screen.getAllByRole('button', { name: 'Report a problem' })).toHaveLength(3);
    expect(
      within(screen.getByText('Order #11').closest('a') as HTMLElement).getByText('DISPUTED'),
    ).toBeTruthy();
    expect(
      screen.getByText('Dispute open — this order is frozen while an admin reviews it.'),
    ).toBeTruthy();
    // The opened dispute appears in the seller's dispute view: the
    // DISPUTED row resolves the dispute by order and renders its
    // evidence panel.
    await waitFor(() => expect(screen.getByText('No evidence photos yet.')).toBeTruthy());
  });

  it('submitting the reason form posts the dispute and the row flips to DISPUTED on refetch', async () => {
    let listCalls = 0;
    getSpy.mockImplementation(((url: string) => {
      if (url === '/seller/orders/summary') {
        return Promise.resolve(summaryEnvelope());
      }
      if (url.endsWith('/dispute')) {
        return Promise.resolve(envelope(fakeDispute));
      }
      if (url.includes('/evidence')) {
        return Promise.resolve(envelope([]));
      }
      // First list read is the PAID row; the invalidation after the
      // open refetches and the same row now reads DISPUTED.
      listCalls += 1;
      return Promise.resolve(
        pageEnvelope(listCalls === 1 ? [paidOrder] : [{ ...paidOrder, status: 'DISPUTED' }]),
      );
    }) as never);
    postSpy.mockResolvedValue(
      envelope({
        ...fakeDispute,
        orderId: 10,
        reason: 'the buyer never confirmed receipt',
      }),
    );

    renderAt();
    await waitFor(() => expect(screen.getByText('Order #10')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Report a problem' }));
    fireEvent.change(screen.getByLabelText('What went wrong?'), {
      target: { value: 'the buyer never confirmed receipt' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Open dispute' }));

    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith('/orders/10/dispute', {
        reason: 'the buyer never confirmed receipt',
      }),
    );
    // The opened dispute appears in the seller's dispute view: the
    // refetched row carries the DISPUTED chip and the frozen note.
    await waitFor(() =>
      expect(
        screen.getByText('Dispute open — this order is frozen while an admin reviews it.'),
      ).toBeTruthy(),
    );
    expect(
      within(screen.getByText('Order #10').closest('a') as HTMLElement).getByText('DISPUTED'),
    ).toBeTruthy();
  });

  it('a window-expired attempt maps the server 422 to plain copy', async () => {
    mockGets(pageEnvelope([paidOrder]));
    postSpy.mockRejectedValue({
      response: {
        status: 422,
        data: { code: 422, message: 'dispute window has closed', data: null },
      },
    });

    renderAt();
    await waitFor(() => expect(screen.getByText('Order #10')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Report a problem' }));
    fireEvent.change(screen.getByLabelText('What went wrong?'), {
      target: { value: 'the buyer never confirmed receipt' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Open dispute' }));

    await waitFor(() =>
      expect(screen.getByText('The dispute window for this order has closed.')).toBeTruthy(),
    );
    // The form stays open — the seller can correct nothing here, but
    // the row is not left in a half-opened state either.
    expect(screen.getByRole('button', { name: 'Open dispute' })).toBeTruthy();
  });
});
