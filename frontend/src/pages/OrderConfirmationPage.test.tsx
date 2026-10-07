import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import { type Order } from '../api/orders';
import { useAuthStore } from '../store/useAuthStore';
import OrderConfirmationPage from './OrderConfirmationPage';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');

const buyer: AuthUser = { id: 5, username: 'buyer', email: 'buyer@example.com', roles: ['USER'] };

const pendingOrder: Order = {
  id: 9,
  itemId: 7,
  buyerId: 5,
  status: 'PENDING',
  amountCents: 12000,
  createdAt: '2026-10-06T17:00:00Z',
};

const paidOrder: Order = { ...pendingOrder, status: 'PAID' };

function envelope(data: unknown) {
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
          <Route path="/orders/:id" element={<OrderConfirmationPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('OrderConfirmationPage pay-now (demo)', () => {
  beforeEach(() => {
    // synchronous reset — store logout() now calls the server (would pollute spies)
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
  });

  it('a PENDING order shows the demo pay button; success refetches the order', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    // initial read -> PENDING; the invalidated refetch after the capture -> PAID
    getSpy
      .mockResolvedValueOnce(envelope(pendingOrder))
      .mockResolvedValue(envelope(paidOrder));
    postSpy.mockResolvedValueOnce(envelope(paidOrder));

    renderAt('/orders/9');
    await waitFor(() => expect(screen.getByText('Order confirmed')).toBeTruthy());

    const button = screen.getByRole('button', { name: 'Pay now (demo)' });
    expect(button).toBeTruthy();
    fireEvent.click(button);

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(1));
    expect(postSpy).toHaveBeenCalledWith('/orders/9/pay', {});

    // success invalidates the orders subtree -> useOrder refetches, PAID lands
    await waitFor(() => expect(screen.getByText('Paid (demo capture) — the seller will now confirm the handoff.')).toBeTruthy());
    expect(getSpy).toHaveBeenCalledTimes(2);
    expect(screen.getByText('PAID')).toBeTruthy();
  });

  it('the button disables and reads "Paying…" while the capture is in flight', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    getSpy
      .mockResolvedValueOnce(envelope(pendingOrder))
      .mockResolvedValue(envelope(paidOrder));
    let resolvePay!: (value: unknown) => void;
    postSpy.mockImplementationOnce(() => new Promise((resolve) => { resolvePay = resolve; }));

    renderAt('/orders/9');
    await waitFor(() => expect(screen.getByText('Order confirmed')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Pay now (demo)' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Paying…' })).toBeTruthy());
    expect((screen.getByRole('button', { name: 'Paying…' }) as HTMLButtonElement).disabled).toBe(true);

    resolvePay(envelope(paidOrder));
    await waitFor(() => expect(screen.getByText('Paid (demo capture) — the seller will now confirm the handoff.')).toBeTruthy());
  });

  it('an already-PAID order shows the paid confirmation, no pay button', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    getSpy.mockResolvedValue(envelope(paidOrder));

    renderAt('/orders/9');
    await waitFor(() => expect(screen.getByText('Paid (demo capture) — the seller will now confirm the handoff.')).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Pay now (demo)' })).toBeNull();
    expect(postSpy).not.toHaveBeenCalled();
  });

  it('a 409 on pay maps to a friendly retry message', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    getSpy.mockResolvedValue(envelope(pendingOrder));
    postSpy.mockRejectedValueOnce(axiosFailure(409, 'order was modified concurrently'));

    renderAt('/orders/9');
    await waitFor(() => expect(screen.getByText('Order confirmed')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Pay now (demo)' }));

    await waitFor(() =>
      expect(screen.getByText(/Someone just acted on this order/)).toBeTruthy(),
    );
  });

  it('a 422 on pay maps to a terminal message', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    getSpy.mockResolvedValue(envelope(pendingOrder));
    postSpy.mockRejectedValueOnce(axiosFailure(422, 'order is not in PENDING status'));

    renderAt('/orders/9');
    await waitFor(() => expect(screen.getByText('Order confirmed')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Pay now (demo)' }));

    await waitFor(() =>
      expect(screen.getByText(/This order can no longer be paid/)).toBeTruthy(),
    );
  });

  it('a 403 on pay maps to a not-your-order message', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    getSpy.mockResolvedValue(envelope(pendingOrder));
    postSpy.mockRejectedValueOnce(axiosFailure(403, 'only the buyer may pay'));

    renderAt('/orders/9');
    await waitFor(() => expect(screen.getByText('Order confirmed')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Pay now (demo)' }));

    await waitFor(() =>
      expect(screen.getByText(/Only the buyer of this order can pay/)).toBeTruthy(),
    );
  });

  it('anonymous visitors see no pay button on a PENDING order', async () => {
    getSpy.mockResolvedValue(envelope(pendingOrder));

    renderAt('/orders/9');
    await waitFor(() => expect(screen.getByText('Order confirmed')).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Pay now (demo)' })).toBeNull();
  });
});
