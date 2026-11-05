import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { api } from '../api/client';
import type { OrderPaymentAttempt } from '../api/orders';
import OrderPaymentAttempts from './OrderPaymentAttempts';

const getSpy = vi.spyOn(api, 'get');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function attemptsPage(content: OrderPaymentAttempt[]) {
  return { content, totalElements: content.length, totalPages: 1, number: 0 };
}

function renderSection() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <OrderPaymentAttempts orderId={9} />
    </QueryClientProvider>,
  );
}

describe('OrderPaymentAttempts (backlog #335)', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('fetches nothing until the buyer opens the section', () => {
    renderSection();
    expect(screen.getByRole('button', { name: 'Show payment attempts' })).toBeTruthy();
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('renders SUCCEEDED, DECLINED and REFUNDED rows, newest first, with the refund reason', async () => {
    getSpy.mockResolvedValueOnce(
      envelope(
        attemptsPage([
          {
            id: 3,
            orderId: 9,
            outcome: 'REFUNDED',
            idempotencyKey: 'rfd_seed_1',
            refundReason: 'buyer asked twice',
            createdAt: '2026-10-06T17:03:00Z',
          },
          {
            id: 2,
            orderId: 9,
            outcome: 'SUCCEEDED',
            idempotencyKey: 'cap_seed_1',
            refundReason: null,
            createdAt: '2026-10-06T17:02:00Z',
          },
          {
            id: 1,
            orderId: 9,
            outcome: 'DECLINED',
            idempotencyKey: 'cap_seed_1',
            refundReason: null,
            createdAt: '2026-10-06T17:01:00Z',
          },
        ]),
      ),
    );

    renderSection();
    fireEvent.click(screen.getByRole('button', { name: 'Show payment attempts' }));

    await waitFor(() => expect(getSpy).toHaveBeenCalledWith('/orders/9/attempts'));
    await waitFor(() => expect(screen.getByText('Payment attempts (demo)')).toBeTruthy());
    // Demo honesty rides with the rows, like the PSP references.
    expect(screen.getByText(/never real processor records/)).toBeTruthy();
    const rows = screen.getByRole('list').querySelectorAll('li');
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain('REFUNDED');
    expect(rows[0].textContent).toContain('buyer asked twice');
    expect(rows[1].textContent).toContain('SUCCEEDED');
    expect(rows[2].textContent).toContain('DECLINED');
    // The operator correlation id is not buyer-meaningful: never rendered.
    expect(screen.queryByText(/cap_seed_1/)).toBeNull();
    expect(screen.queryByText(/rfd_seed_1/)).toBeNull();
  });

  it('renders nothing extra when the list is empty', async () => {
    getSpy.mockResolvedValueOnce(envelope(attemptsPage([])));

    renderSection();
    fireEvent.click(screen.getByRole('button', { name: 'Show payment attempts' }));

    await waitFor(() => expect(getSpy).toHaveBeenCalledWith('/orders/9/attempts'));
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'Hide payment attempts' }),
      ).toBeTruthy(),
    );
    expect(screen.queryByText('Payment attempts (demo)')).toBeNull();
    expect(screen.queryByRole('list')).toBeNull();
  });
});
