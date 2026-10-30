import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import PartialRefundNote from './PartialRefundNote';
import type { Order } from '../api/orders';

function makeOrder(overrides: Partial<Order>): Order {
  return {
    id: 11,
    itemId: 7,
    buyerId: 5,
    status: 'PAID',
    amountCents: 2000,
    createdAt: '2026-10-20T00:00:00Z',
    captureId: 'cap_mock_1',
    refundId: null,
    ...overrides,
  };
}

afterEach(cleanup);

describe('PartialRefundNote (backlog #283)', () => {
  it('renders the partial amount for a PAID order refunded in part', () => {
    render(<PartialRefundNote order={makeOrder({ refundedAmountCents: 500 })} />);

    expect(screen.getByText('Partially refunded: ¥5.00 of ¥20.00')).toBeTruthy();
  });

  it('renders nothing for an untouched order (refunded 0)', () => {
    const { container } = render(<PartialRefundNote order={makeOrder({ refundedAmountCents: 0 })} />);

    expect(container.firstChild).toBeNull();
  });

  it('renders nothing when the refund field is absent (pre-#210 shape)', () => {
    const order = makeOrder({});
    delete order.refundedAmountCents;
    const { container } = render(<PartialRefundNote order={order} />);

    expect(container.firstChild).toBeNull();
  });

  it('renders nothing once the order is fully refunded', () => {
    const { container } = render(
      <PartialRefundNote order={makeOrder({ status: 'REFUNDED', refundedAmountCents: 2000 })} />,
    );

    expect(container.firstChild).toBeNull();
  });

  it('renders nothing when the refunded total meets or exceeds the amount', () => {
    const { container } = render(
      <PartialRefundNote order={makeOrder({ refundedAmountCents: 2500 })} />,
    );

    expect(container.firstChild).toBeNull();
  });
});
