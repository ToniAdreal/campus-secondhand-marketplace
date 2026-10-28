import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from '../api/client';
import type { OrderReceipt as ReceiptData } from '../api/orders';
import OrderReceipt from './OrderReceipt';

const getSpy = vi.spyOn(api, 'get');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

const paidReceipt: ReceiptData = {
  orderId: 9,
  status: 'PAID',
  paid: true,
  itemId: 7,
  itemTitle: 'Film camera',
  amountCents: 88000,
  formattedPrice: '¥880.00',
  buyerUsername: 'buyer-1',
  sellerUsername: 'seller-1',
  createdAt: '2026-10-16T08:00:00Z',
  paidAt: '2026-10-16T08:05:00Z',
  completedAt: null,
  captureId: 'cap_mock_0123456789abcdef',
  refundId: null,
  refundReason: null,
  refundedAmountCents: null,
  disputeResolutionNote: null,
};

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe('OrderReceipt (backlog #180)', () => {
  afterEach(() => {
    cleanup();
    getSpy.mockReset();
  });

  it('makes no receipt fetch until the Receipt button is clicked', async () => {
    getSpy.mockResolvedValue(envelope(paidReceipt));
    render(<OrderReceipt orderId={9} />, { wrapper });
    expect(getSpy).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Receipt' }));

    await waitFor(() => expect(getSpy).toHaveBeenCalledTimes(1));
    expect(getSpy).toHaveBeenCalledWith('/orders/9/receipt');
  });

  it('renders the paid receipt fields and prints via window.print', async () => {
    const printSpy = vi.spyOn(window, 'print').mockImplementation(() => {});
    getSpy.mockResolvedValue(envelope(paidReceipt));
    render(<OrderReceipt orderId={9} />, { wrapper });

    fireEvent.click(screen.getByRole('button', { name: 'Receipt' }));

    expect(await screen.findByText('Receipt — order #9')).toBeTruthy();
    expect(screen.getAllByText('PAID').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('Film camera')).toBeTruthy();
    expect(screen.getByText('¥880.00')).toBeTruthy();
    expect(screen.getByText('buyer-1')).toBeTruthy();
    expect(screen.getByText('seller-1')).toBeTruthy();
    expect(screen.getByText('Payment reference (demo):')).toBeTruthy();
    expect(screen.getByText('cap_mock_0123456789abcdef')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Print receipt' }));
    expect(printSpy).toHaveBeenCalledTimes(1);
    printSpy.mockRestore();
  });

  it('marks a PENDING receipt UNPAID with no payment reference', async () => {
    getSpy.mockResolvedValue(
      envelope({
        ...paidReceipt,
        status: 'PENDING',
        paid: false,
        paidAt: null,
        captureId: null,
      }),
    );
    render(<OrderReceipt orderId={9} />, { wrapper });

    fireEvent.click(screen.getByRole('button', { name: 'Receipt' }));

    expect(await screen.findByText('UNPAID')).toBeTruthy();
    expect(
      screen.getByText('This order has not been paid yet — no payment reference exists.'),
    ).toBeTruthy();
    expect(screen.queryByText(/Payment reference/)).toBeNull();
  });

  it('renders the refund reason on a refunded receipt (backlog #239)', async () => {
    getSpy.mockResolvedValue(
      envelope({
        ...paidReceipt,
        status: 'REFUNDED',
        refundId: 'rfd_mock_0123456789abcdef',
        refundReason: 'Item arrived damaged',
      }),
    );
    render(<OrderReceipt orderId={9} />, { wrapper });

    fireEvent.click(screen.getByRole('button', { name: 'Receipt' }));

    expect(await screen.findByText('Refund reason:')).toBeTruthy();
    expect(screen.getByText('Item arrived damaged')).toBeTruthy();
  });

  it('renders the refunded total and dispute note on a dispute-refunded receipt (backlog #257)', async () => {
    getSpy.mockResolvedValue(
      envelope({
        ...paidReceipt,
        status: 'REFUNDED',
        refundId: 'rfd_mock_0123456789abcdef',
        refundedAmountCents: 88000,
        disputeResolutionNote: 'Parcel never arrived; refunding the buyer.',
      }),
    );
    render(<OrderReceipt orderId={9} />, { wrapper });

    fireEvent.click(screen.getByRole('button', { name: 'Receipt' }));

    expect(await screen.findByText('Refunded:')).toBeTruthy();
    // ¥880.00 appears twice: the order amount and the refunded total.
    expect(screen.getAllByText('¥880.00').length).toBe(2);
    expect(screen.getByText('Dispute resolution:')).toBeTruthy();
    expect(screen.getByText('Parcel never arrived; refunding the buyer.')).toBeTruthy();
  });

  it('maps a 404 to the generic not-available copy', async () => {
    getSpy.mockRejectedValue({ response: { data: { code: 404, message: 'order not found' } } });
    render(<OrderReceipt orderId={9} />, { wrapper });

    fireEvent.click(screen.getByRole('button', { name: 'Receipt' }));

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByText('This receipt is not available.')).toBeTruthy();
  });
});
