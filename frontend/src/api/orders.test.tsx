import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import { itemKeys } from './items';
import {
  describeCancelError,
  describeCompleteError,
  describeOrderError,
  describePayError,
  describeRefundError,
  orderKeys,
  useCancelOrder,
  useCompleteOrder,
  usePayOrder,
  useRefundOrder,
  type Order,
} from './orders';

const postSpy = vi.spyOn(api, 'post');

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
  ])('%s', (_label, error, expected) => {
    expect(describeCancelError(error)).toBe(expected);
  });
});

describe('describeCompleteError', () => {
  it.each([
    ['not-paid code', envelopeError(422, 'unprocessable'), 'Only a paid order can be completed — this one is not paid yet, or is already finished.'],
    ['already-completed wording', envelopeError(422, 'order is already COMPLETED'), 'This order was already completed.'],
    ['conflict code', envelopeError(409, 'conflict'), 'Someone just acted on this order — please refresh and try again.'],
    ['version wording', envelopeError(0, 'stale version'), 'Someone just acted on this order — please refresh and try again.'],
    ['forbidden', envelopeError(403, 'forbidden'), 'Only the seller of this listing can complete the order.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in to complete this order.'],
    ['unknown order', envelopeError(404, 'not found'), 'This order no longer exists.'],
    ['unknown error keeps the server message', envelopeError(500, 'database unavailable'), 'database unavailable'],
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
  ])('%s', (_label, error, expected) => {
    expect(describeRefundError(error)).toBe(expected);
  });
});

describe('describeOrderError', () => {
  it.each([
    ['conflict code', envelopeError(409, 'conflict'), 'Someone just grabbed this item — it is no longer available.'],
    ['own-listing code', envelopeError(422, 'unprocessable'), 'You cannot buy your own listing.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in to buy this item.'],
    ['unknown listing', envelopeError(404, 'not found'), 'This listing no longer exists.'],
    ['unknown error keeps the server message', envelopeError(500, 'database unavailable'), 'database unavailable'],
  ])('%s', (_label, error, expected) => {
    expect(describeOrderError(error)).toBe(expected);
  });
});

describe('order mutation invalidation', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('useCompleteOrder posts to /complete and invalidates orders + items', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    postSpy.mockResolvedValueOnce(envelope(fakeOrder));

    const { result } = renderHook(() => useCompleteOrder(), { wrapper: wrapperFor(queryClient) });
    const returned = await result.current.mutateAsync(9);

    expect(postSpy).toHaveBeenCalledWith('/orders/9/complete', {});
    expect(returned).toEqual(fakeOrder);
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: orderKeys.all });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: itemKeys.all });
    expect(invalidateSpy).toHaveBeenCalledTimes(2);
  });

  it('useRefundOrder posts to /refund and invalidates orders + items', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    postSpy.mockResolvedValueOnce(envelope({ ...fakeOrder, status: 'REFUNDED' }));

    const { result } = renderHook(() => useRefundOrder(), { wrapper: wrapperFor(queryClient) });
    await result.current.mutateAsync(9);

    expect(postSpy).toHaveBeenCalledWith('/orders/9/refund', {});
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: orderKeys.all });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: itemKeys.all });
    expect(invalidateSpy).toHaveBeenCalledTimes(2);
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

  it('a failed complete does not invalidate anything', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    postSpy.mockRejectedValueOnce(new Error('network down'));

    const { result } = renderHook(() => useCompleteOrder(), { wrapper: wrapperFor(queryClient) });
    await expect(result.current.mutateAsync(9)).rejects.toThrow('network down');

    expect(invalidateSpy).not.toHaveBeenCalled();
  });
});
