import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from '../api/client';
import type { Order } from '../api/orders';
import OrderShipmentControl from './OrderShipmentControl';

const postSpy = vi.spyOn(api, 'post');

const shippedOrder: Order = {
  id: 9,
  itemId: 7,
  buyerId: 5,
  status: 'SHIPPED',
  amountCents: 12000,
  createdAt: '2026-10-09T08:00:00Z',
  captureId: 'cap_mock_0123456789abcdef',
  refundId: null,
  carrier: 'SF Express',
  trackingNumber: 'SF123456789',
  shippedAt: '2026-10-10T08:00:00Z',
};

function wrapperFor(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

describe('OrderShipmentControl (backlog #222)', () => {
  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
  });

  it('a SHIPPED order shows the carrier + tracking number and a confirm button', () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    render(<OrderShipmentControl order={shippedOrder} />, {
      wrapper: wrapperFor(queryClient),
    });
    expect(
      screen.getByText('Shipped via SF Express — tracking SF123456789.'),
    ).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Confirm receipt' })).toBeTruthy();
  });

  it('confirming receipt posts to /confirm-receipt and shows the success note', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    postSpy.mockResolvedValueOnce(envelope({ ...shippedOrder, status: 'COMPLETED' }));
    render(<OrderShipmentControl order={shippedOrder} />, {
      wrapper: wrapperFor(queryClient),
    });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm receipt' }));
    await waitFor(() =>
      expect(screen.getByText('Receipt confirmed — thanks! The order is now complete.')).toBeTruthy(),
    );
    expect(postSpy).toHaveBeenCalledWith('/orders/9/confirm-receipt', {});
  });

  it('a PAID order renders nothing', () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const { container } = render(
      <OrderShipmentControl order={{ ...shippedOrder, status: 'PAID' }} />,
      { wrapper: wrapperFor(queryClient) },
    );
    expect(container.firstChild).toBeNull();
    expect(screen.queryByRole('button', { name: 'Confirm receipt' })).toBeNull();
  });
});
