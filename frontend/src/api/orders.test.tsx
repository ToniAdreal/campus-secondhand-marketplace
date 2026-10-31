import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import { itemKeys } from './items';
import {
  describeCancelError,
  describeCompleteError,
  describeConfirmReceiptError,
  describeOrderError,
  describePayError,
  describeRefundError,
  describeRegenerateHandoverError,
  describeReceiptError,
  describeShipError,
  orderKeys,
  useCancelOrder,
  useCompleteOrder,
  useConfirmReceiptOrder,
  usePayOrder,
  useRefundOrder,
  useRegenerateHandoverCode,
  useOrderReceipt,
  useSellerSalesSummary,
  useShipOrder,
  type Order,
  type OrderReceipt,
  type SellerSalesSummary,
} from './orders';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function envelopeError(code: number, message: string) {
  return { response: { data: { code, message } } };
}

const fakeOrder: Order = {
  id: 9,
  itemId: 7,
  buyerId: 5,
  status: 'PAID',
  amountCents: 12000,
  createdAt: '2026-10-09T08:00:00Z',
  captureId: 'cap_mock_0123456789abcdef',
  refundId: null,
};

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function wrapperFor(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

describe('describePayError', () => {
  it.each([
    ['declined code', envelopeError(402, 'payment required'), 'Your payment was declined — the order is still pending, please try a different payment method.'],
    ['declined wording', envelopeError(0, 'card declined by issuer'), 'Your payment was declined — the order is still pending, please try a different payment method.'],
    ['conflict code', envelopeError(409, 'conflict'), 'Someone just acted on this order — please refresh and try again.'],
    ['optimistic-lock wording', envelopeError(0, 'optimistic locking failure'), 'Someone just acted on this order — please refresh and try again.'],
    ['terminal code', envelopeError(422, 'unprocessable'), 'This order can no longer be paid — it was cancelled, completed, or refunded.'],
    ['forbidden', envelopeError(403, 'forbidden'), 'Only the buyer of this order can pay for it.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in to pay for this order.'],
    ['unknown order', envelopeError(404, 'not found'), 'This order no longer exists.'],
    ['unknown error keeps the server message', envelopeError(500, 'psp timeout'), 'psp timeout'],
    ['no envelope at all', new Error('boom'), 'Something went wrong. Please try again.'],
    // Stryker backlog #287, orders tranche: only a present-but-dataless
    // response (or an empty-data response) distinguishes
    // response?.data?.code/message from its optional-chain mutants —
    // the no-envelope case above leaves response undefined, so the
    // first ?. still guards both chains.
    ['response without data', { response: {} }, 'Something went wrong. Please try again.'],
    ['response with empty data', { response: { data: {} } }, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describePayError(error)).toBe(expected);
  });
});

describe('describeCancelError', () => {
  it.each([
    ['terminal code', envelopeError(422, 'unprocessable'), 'This order can no longer be cancelled — it was already paid, completed, or cancelled.'],
    ['not-pending wording', envelopeError(0, 'order is not in PENDING'), 'This order can no longer be cancelled — it was already paid, completed, or cancelled.'],
    ['conflict code', envelopeError(409, 'conflict'), 'Someone just acted on this order — please refresh and try again.'],
    ['forbidden', envelopeError(403, 'forbidden'), 'Only the buyer of this order can cancel it.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in to cancel this order.'],
    ['unknown order', envelopeError(404, 'not found'), 'This order no longer exists.'],
    ['unknown error keeps the server message', envelopeError(500, 'database unavailable'), 'database unavailable'],
    ['no envelope at all', {}, 'Something went wrong. Please try again.'],
    // Stryker backlog #287, orders tranche: present-but-dataless and
    // empty-data shapes pin the response?.data?.code/message chains
    // (see the describePayError note above).
    ['response without data', { response: {} }, 'Something went wrong. Please try again.'],
    ['response with empty data', { response: { data: {} } }, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeCancelError(error)).toBe(expected);
  });
});

describe('describeCompleteError', () => {
  it.each([
    ['handover code', envelopeError(400, 'invalid handover code'), 'That handover code is not right — ask the buyer for the 6-digit code from their order page and try again.'],
    ['handover wording', envelopeError(0, 'handover code locked'), 'That handover code is not right — ask the buyer for the 6-digit code from their order page and try again.'],
    ['not-paid code', envelopeError(422, 'unprocessable'), 'Only a paid order can be completed — this one is not paid yet, or is already finished.'],
    ['already-completed wording', envelopeError(422, 'order is already COMPLETED'), 'This order was already completed.'],
    ['shipped wording (backlog #222)', envelopeError(422, 'order is SHIPPED, completion is not allowed'), 'This order has already shipped — only the buyer can confirm receipt now.'],
    ['conflict code', envelopeError(409, 'conflict'), 'Someone just acted on this order — please refresh and try again.'],
    ['version wording', envelopeError(0, 'stale version'), 'Someone just acted on this order — please refresh and try again.'],
    ['forbidden', envelopeError(403, 'forbidden'), 'Only the seller of this listing can complete the order.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in to complete this order.'],
    ['unknown order', envelopeError(404, 'not found'), 'This order no longer exists.'],
    ['unknown error keeps the server message', envelopeError(500, 'database unavailable'), 'database unavailable'],
    // Stryker backlog #287, orders tranche: code 400 alone (no handover
    // wording in the message) pins the code side of the 400-or-regex
    // condition — the existing cases only reach it via the message.
    ['handover code alone', envelopeError(400, 'bad request'), 'That handover code is not right — ask the buyer for the 6-digit code from their order page and try again.'],
    ['response without data', { response: {} }, 'Something went wrong. Please try again.'],
    ['response with empty data', { response: { data: {} } }, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeCompleteError(error)).toBe(expected);
  });
});

describe('describeRefundError', () => {
  it.each([
    ['not-paid code', envelopeError(422, 'unprocessable'), 'Only a paid order can be refunded — this one is not paid yet, or is already finished.'],
    ['already-refunded wording', envelopeError(422, 'order is already REFUNDED'), 'This order was already refunded.'],
    ['already-completed wording', envelopeError(422, 'order is already COMPLETED'), 'This order was already completed — it can no longer be refunded.'],
    ['conflict code', envelopeError(409, 'conflict'), 'Someone just acted on this order — please refresh and try again.'],
    ['forbidden', envelopeError(403, 'forbidden'), 'Only the seller of this listing can refund the order.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in to refund this order.'],
    ['unknown order', envelopeError(404, 'not found'), 'This order no longer exists.'],
    ['unknown error keeps the server message', envelopeError(500, 'database unavailable'), 'database unavailable'],
    ['no envelope at all', {}, 'Something went wrong. Please try again.'],
    ['over-remaining amount (backlog #210)', envelopeError(422, 'refund amount exceeds the remaining refundable amount'), 'That amount is more than what is left to refund on this order.'],
    ['non-positive amount (backlog #210)', envelopeError(422, 'refund amount must be positive'), 'Enter a refund amount greater than zero.'],
    // Stryker backlog #287, orders tranche: the 400-and-reason branch
    // had no test at all in the 2026-10-31 re-measurement. The
    // positive case plus the two single-side negatives pin the
    // conjunction (code alone and wording alone must not fire it).
    ['missing reason code', envelopeError(400, 'a reason is required'), 'Enter a reason for the refund.'],
    ['400 without reason wording', envelopeError(400, 'bad request'), 'bad request'],
    ['reason wording without 400', envelopeError(500, 'a reason is required'), 'a reason is required'],
    ['response without data', { response: {} }, 'Something went wrong. Please try again.'],
    ['response with empty data', { response: { data: {} } }, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeRefundError(error)).toBe(expected);
  });
});

describe('describeShipError', () => {
  it.each([
    ['blank body code', envelopeError(400, 'carrier is required'), 'Enter a carrier and tracking number (tracking number at most 128 characters).'],
    ['not-paid code', envelopeError(422, 'unprocessable'), 'Only a paid order can be shipped — this one is not paid, or already shipped or completed.'],
    ['conflict code', envelopeError(409, 'conflict'), 'Someone just acted on this order — please refresh and try again.'],
    ['forbidden', envelopeError(403, 'forbidden'), 'Only the seller of this listing can ship the order.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in to ship the order.'],
    ['unknown order', envelopeError(404, 'not found'), 'This order no longer exists.'],
    ['unknown error keeps the server message', envelopeError(500, 'database unavailable'), 'database unavailable'],
    // Stryker backlog #287, orders tranche: present-but-dataless and
    // empty-data shapes pin the response?.data?.code/message chains.
    ['response without data', { response: {} }, 'Something went wrong. Please try again.'],
    ['response with empty data', { response: { data: {} } }, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeShipError(error)).toBe(expected);
  });
});

describe('describeConfirmReceiptError', () => {
  it.each([
    ['not-shipped code', envelopeError(422, 'unprocessable'), 'Only a shipped order can be confirmed — this one has not shipped yet, or is already finished.'],
    ['conflict code', envelopeError(409, 'conflict'), 'Someone just acted on this order — please refresh and try again.'],
    ['forbidden', envelopeError(403, 'forbidden'), 'Only the buyer of this order can confirm receipt.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in to confirm receipt.'],
    ['unknown order', envelopeError(404, 'not found'), 'This order no longer exists.'],
    ['unknown error keeps the server message', envelopeError(500, 'database unavailable'), 'database unavailable'],
    // Stryker backlog #287, orders tranche: present-but-dataless and
    // empty-data shapes pin the response?.data?.code/message chains.
    ['response without data', { response: {} }, 'Something went wrong. Please try again.'],
    ['response with empty data', { response: { data: {} } }, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeConfirmReceiptError(error)).toBe(expected);
  });
});

describe('describeRegenerateHandoverError', () => {
  it.each([
    ['forbidden', envelopeError(403, 'forbidden'), 'Only the buyer of this order can generate a handover code.'],
    ['not-paid code', envelopeError(422, 'unprocessable'), 'A handover code can only be generated while the order is paid.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in to generate a handover code.'],
    ['unknown order', envelopeError(404, 'not found'), 'This order no longer exists.'],
    // Stryker backlog #287, orders tranche: this mapper had no
    // fallback case at all, so its fallback line was unmeasured;
    // the dataless shapes reach it (and the 404 line above it).
    ['response without data', { response: {} }, 'Something went wrong. Please try again.'],
    ['response with empty data', { response: { data: {} } }, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeRegenerateHandoverError(error)).toBe(expected);
  });
});

describe('describeOrderError', () => {
  it.each([
    ['conflict code', envelopeError(409, 'conflict'), 'Someone just grabbed this item — it is no longer available.'],
    ['own-listing code', envelopeError(422, 'unprocessable'), 'You cannot buy your own listing.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in to buy this item.'],
    ['unknown listing', envelopeError(404, 'not found'), 'This listing no longer exists.'],
    ['unknown error keeps the server message', envelopeError(500, 'database unavailable'), 'database unavailable'],
    // Stryker backlog #287, orders tranche: present-but-dataless and
    // empty-data shapes pin the response?.data?.code/message chains.
    ['response without data', { response: {} }, 'Something went wrong. Please try again.'],
    ['response with empty data', { response: { data: {} } }, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeOrderError(error)).toBe(expected);
  });
});

describe('order mutation invalidation', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('useCompleteOrder posts to /complete with the handover code and invalidates orders + items', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    postSpy.mockResolvedValueOnce(envelope(fakeOrder));

    const { result } = renderHook(() => useCompleteOrder(), { wrapper: wrapperFor(queryClient) });
    const returned = await result.current.mutateAsync({ orderId: 9, handoverCode: '123456' });

    expect(postSpy).toHaveBeenCalledWith('/orders/9/complete', { handoverCode: '123456' });
    expect(returned).toEqual(fakeOrder);
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: orderKeys.all });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: itemKeys.all });
    expect(invalidateSpy).toHaveBeenCalledTimes(2);
  });

  it('useShipOrder posts to /ship with carrier + tracking and invalidates orders + items', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    postSpy.mockResolvedValueOnce(envelope({ ...fakeOrder, status: 'SHIPPED' }));

    const { result } = renderHook(() => useShipOrder(), { wrapper: wrapperFor(queryClient) });
    const returned = await result.current.mutateAsync({
      orderId: 9,
      carrier: 'SF Express',
      trackingNumber: 'SF123456789',
    });

    expect(postSpy).toHaveBeenCalledWith('/orders/9/ship', {
      carrier: 'SF Express',
      trackingNumber: 'SF123456789',
    });
    expect(returned.status).toBe('SHIPPED');
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: orderKeys.all });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: itemKeys.all });
    expect(invalidateSpy).toHaveBeenCalledTimes(2);
  });

  it('useConfirmReceiptOrder posts to /confirm-receipt and invalidates orders + items', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    postSpy.mockResolvedValueOnce(envelope({ ...fakeOrder, status: 'COMPLETED' }));

    const { result } = renderHook(() => useConfirmReceiptOrder(), {
      wrapper: wrapperFor(queryClient),
    });
    const returned = await result.current.mutateAsync(9);

    expect(postSpy).toHaveBeenCalledWith('/orders/9/confirm-receipt', {});
    expect(returned.status).toBe('COMPLETED');
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: orderKeys.all });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: itemKeys.all });
    expect(invalidateSpy).toHaveBeenCalledTimes(2);
  });

  it('useRefundOrder posts to /refund and invalidates orders + items', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    postSpy.mockResolvedValueOnce(envelope({ ...fakeOrder, status: 'REFUNDED' }));

    const { result } = renderHook(() => useRefundOrder(), { wrapper: wrapperFor(queryClient) });
    await result.current.mutateAsync({ orderId: 9, reason: 'Item arrived damaged' });

    expect(postSpy).toHaveBeenCalledWith('/orders/9/refund', { reason: 'Item arrived damaged' });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: orderKeys.all });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: itemKeys.all });
    expect(invalidateSpy).toHaveBeenCalledTimes(2);
  });

  it('useRefundOrder with an amount posts {amountCents} for a partial refund (backlog #210)', async () => {
    const queryClient = makeClient();
    postSpy.mockResolvedValueOnce(
      envelope({ ...fakeOrder, status: 'PAID', refundedAmountCents: 2000 }),
    );

    const { result } = renderHook(() => useRefundOrder(), { wrapper: wrapperFor(queryClient) });
    const refunded = await result.current.mutateAsync({
      orderId: 9,
      amountCents: 2000,
      reason: 'Missing accessory',
    });

    expect(postSpy).toHaveBeenCalledWith('/orders/9/refund', {
      amountCents: 2000,
      reason: 'Missing accessory',
    });
    expect(refunded.refundedAmountCents).toBe(2000);
    expect(refunded.status).toBe('PAID');
  });

  it('usePayOrder posts to /pay and invalidates the orders root only', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    postSpy.mockResolvedValueOnce(envelope(fakeOrder));

    const { result } = renderHook(() => usePayOrder(), { wrapper: wrapperFor(queryClient) });
    await result.current.mutateAsync(9);

    expect(postSpy).toHaveBeenCalledWith('/orders/9/pay', {});
    expect(invalidateSpy).toHaveBeenCalledTimes(1);
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['orders'] });
  });

  it('pay/refund mutation results carry the PSP references through unchanged (backlog #119)', async () => {
    const queryClient = makeClient();
    // fakeOrder is PAID with a capture id and no refund id yet
    postSpy.mockResolvedValueOnce(envelope(fakeOrder));

    const { result } = renderHook(() => usePayOrder(), { wrapper: wrapperFor(queryClient) });
    const paid = await result.current.mutateAsync(9);

    expect(paid.captureId).toBe('cap_mock_0123456789abcdef');
    expect(paid.refundId).toBeNull();

    const refundedOrder: Order = { ...fakeOrder, status: 'REFUNDED', refundId: 'rfd_mock_0123456789abcdef' };
    postSpy.mockResolvedValueOnce(envelope(refundedOrder));

    const { result: refundResult } = renderHook(() => useRefundOrder(), {
      wrapper: wrapperFor(queryClient),
    });
    const refunded = await refundResult.current.mutateAsync({
      orderId: 9,
      reason: 'Item arrived damaged',
    });

    // a REFUNDED order keeps its capture id and gains the refund id — both
    // ride the mutation payload, no extra fetch is needed to display them
    expect(refunded.captureId).toBe('cap_mock_0123456789abcdef');
    expect(refunded.refundId).toBe('rfd_mock_0123456789abcdef');
  });

  it('useCancelOrder posts to /cancel and invalidates orders + items', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    postSpy.mockResolvedValueOnce(envelope({ ...fakeOrder, status: 'CANCELLED' }));

    const { result } = renderHook(() => useCancelOrder(), { wrapper: wrapperFor(queryClient) });
    await result.current.mutateAsync(9);

    expect(postSpy).toHaveBeenCalledWith('/orders/9/cancel', {});
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: orderKeys.all });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: itemKeys.all });
  });

  it('useSellerSalesSummary reads /seller/orders/summary under its own key', async () => {
    const summary: SellerSalesSummary = {
      totalOrders: 5,
      pendingCount: 1,
      paidCount: 1,
      completedCount: 2,
      cancelledCount: 1,
      refundedCount: 0,
      grossCents: 50500,
      currentMonth: { year: 2026, month: 10, totalOrders: 3, completedCount: 1, grossCents: 30000 },
    };
    getSpy.mockResolvedValueOnce(envelope(summary));

    const queryClient = makeClient();
    const { result } = renderHook(() => useSellerSalesSummary(), {
      wrapper: wrapperFor(queryClient),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(getSpy).toHaveBeenCalledWith('/seller/orders/summary');
    expect(result.current.data).toEqual(summary);
    expect(queryClient.getQueryState(orderKeys.sellerSummary())?.data).toEqual(summary);
  });

  it('useSellerSalesSummary stays idle when disabled (anonymous page guard)', async () => {
    const { result } = renderHook(() => useSellerSalesSummary(false), {
      wrapper: wrapperFor(makeClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('useRegenerateHandoverCode posts to the regenerate endpoint and returns the one-time code', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    postSpy.mockResolvedValueOnce(envelope({ ...fakeOrder, handoverCode: '654321' }));

    const { result } = renderHook(() => useRegenerateHandoverCode(), {
      wrapper: wrapperFor(queryClient),
    });
    const returned = await result.current.mutateAsync(9);

    expect(postSpy).toHaveBeenCalledWith('/orders/9/handover-code/regenerate', {});
    expect(returned.handoverCode).toBe('654321');
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: orderKeys.all });
  });

  it('describeReceiptError maps 404/401 and keeps other server messages', () => {
    expect(describeReceiptError(envelopeError(404, 'order not found'))).toBe(
      'This receipt is not available.',
    );
    expect(describeReceiptError(envelopeError(401, 'unauthorized'))).toBe(
      'Please log in to view this receipt.',
    );
    expect(describeReceiptError(envelopeError(500, 'boom'))).toBe('boom');
    expect(describeReceiptError(new Error('x'))).toBe(
      'Something went wrong. Please try again.',
    );
    // Stryker backlog #287, orders tranche: present-but-dataless and
    // empty-data shapes pin this mapper's response?.data?.code/message
    // chains (its fallback reads response?.data?.message directly).
    expect(describeReceiptError({ response: {} })).toBe(
      'Something went wrong. Please try again.',
    );
    expect(describeReceiptError({ response: { data: {} } })).toBe(
      'Something went wrong. Please try again.',
    );
  });

  it('useOrderReceipt fetches the receipt endpoint under its own key', async () => {
    const receipt: OrderReceipt = {
      orderId: 9,
      status: 'PAID',
      paid: true,
      itemId: 7,
      itemTitle: 'Film camera',
      amountCents: 12000,
      formattedPrice: '¥120.00',
      buyerUsername: 'buyer-1',
      sellerUsername: 'seller-1',
      createdAt: '2026-10-09T08:00:00Z',
      paidAt: '2026-10-09T08:05:00Z',
      completedAt: null,
      captureId: 'cap_mock_0123456789abcdef',
      refundId: null,
      refundReason: null,
      refundedAmountCents: null,
      disputeResolutionNote: null,
    };
    getSpy.mockResolvedValue(envelope(receipt));

    const queryClient = makeClient();
    const { result } = renderHook(() => useOrderReceipt(9), {
      wrapper: wrapperFor(queryClient),
    });

    await waitFor(() => expect(result.current.data).toEqual(receipt));
    expect(getSpy).toHaveBeenCalledWith('/orders/9/receipt');
    expect(queryClient.getQueryState(orderKeys.receipt(9))?.data).toEqual(receipt);
  });

  it('useOrderReceipt stays idle when disabled (lazy receipt view)', async () => {
    const { result } = renderHook(() => useOrderReceipt(9, false), {
      wrapper: wrapperFor(makeClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('a failed complete does not invalidate anything', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    postSpy.mockRejectedValueOnce(new Error('network down'));

    const { result } = renderHook(() => useCompleteOrder(), { wrapper: wrapperFor(queryClient) });
    await expect(result.current.mutateAsync({ orderId: 9 })).rejects.toThrow('network down');

    expect(invalidateSpy).not.toHaveBeenCalled();
  });
});
