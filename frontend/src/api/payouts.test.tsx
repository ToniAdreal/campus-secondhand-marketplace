import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import type { Page } from './items';
import {
  payoutKeys,
  useSellerPayoutsInfinite,
  useSellerPayoutTotals,
  type SellerPayout,
  type SellerPayoutTotals,
} from './payouts';

const getSpy = vi.spyOn(api, 'get');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

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

function payout(id: number, overrides: Partial<SellerPayout> = {}): SellerPayout {
  return {
    id,
    orderId: 100 + id,
    sellerId: 7,
    amountCents: 5000,
    adjustmentsCents: 0,
    netCents: 5000,
    status: 'PAYABLE',
    createdAt: '2026-10-09T08:00:00Z',
    payableAt: '2026-10-09T08:00:00Z',
    paidAt: null,
    ...overrides,
  };
}

function page(content: SellerPayout[], number: number, totalPages: number): Page<SellerPayout> {
  return { content, totalElements: content.length, totalPages, number };
}

const totalsFixture: SellerPayoutTotals = {
  pendingCount: 1,
  pendingCents: 5000,
  payableCount: 2,
  payableCents: 12000,
  paidCount: 3,
  paidCents: 30000,
  adjustmentsCents: -1000,
  netCents: 46000,
};

describe('payoutKeys', () => {
  it('list and totals hang off [orders, seller, payouts] as sibling leaves', () => {
    expect(payoutKeys.list()).toEqual(['orders', 'seller', 'payouts', 'list']);
    expect(payoutKeys.totals()).toEqual(['orders', 'seller', 'payouts', 'totals']);
    // The two subtrees must never collide: invalidating one must not
    // refetch the other.
    expect(payoutKeys.list()).not.toEqual(payoutKeys.totals());
  });

  it('never collides with the seller order list key', () => {
    // SellerOrdersPage's list key is ['orders', 'seller', 'list'] (#72);
    // a payout refetch must not drag the seller order list with it.
    expect(payoutKeys.list()).not.toEqual(['orders', 'seller', 'list']);
  });
});

describe('useSellerPayoutsInfinite', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('GETs /seller/payouts with page 0 and the default size 20, and unwraps the page envelope', async () => {
    const firstPage = page([payout(1), payout(2)], 0, 2);
    getSpy.mockResolvedValueOnce(envelope(firstPage));

    const { result } = renderHook(() => useSellerPayoutsInfinite(), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledTimes(1);
    expect(getSpy).toHaveBeenCalledWith('/seller/payouts', { params: { page: 0, size: 20 } });
    expect(result.current.data?.pages).toEqual([firstPage]);
    expect(result.current.hasNextPage).toBe(true);
  });

  it('sends a caller-supplied size through on every page', async () => {
    getSpy
      .mockResolvedValueOnce(envelope(page([payout(1)], 0, 2)))
      .mockResolvedValueOnce(envelope(page([payout(2)], 1, 2)));

    const { result, rerender } = renderHook(() => useSellerPayoutsInfinite(5), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    await act(async () => {
      await result.current.fetchNextPage();
    });
    rerender();

    expect(getSpy).toHaveBeenCalledTimes(2);
    expect(getSpy).toHaveBeenCalledWith('/seller/payouts', { params: { page: 0, size: 5 } });
    expect(getSpy).toHaveBeenLastCalledWith('/seller/payouts', { params: { page: 1, size: 5 } });
    expect(result.current.data?.pages).toHaveLength(2);
    expect(result.current.hasNextPage).toBe(false);
  });

  it('derives no next page when the list fits on a single page', async () => {
    getSpy.mockResolvedValueOnce(envelope(page([payout(1)], 0, 1)));

    const { result } = renderHook(() => useSellerPayoutsInfinite(), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.hasNextPage).toBe(false);
  });

  it('stays idle when disabled (anonymous page guard)', async () => {
    const { result } = renderHook(() => useSellerPayoutsInfinite(20, false), {
      wrapper: wrapperFor(makeClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('propagates the failure shape so the page can render its error state', async () => {
    const failure = {
      isAxiosError: true,
      response: { status: 403, data: { code: 403, message: 'forbidden', data: null } },
    };
    getSpy.mockRejectedValueOnce(failure);

    const { result } = renderHook(() => useSellerPayoutsInfinite(), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(result.current.error).toBe(failure);
    expect(result.current.data).toBeUndefined();
  });
});

describe('useSellerPayoutTotals', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('GETs /seller/payouts/totals with no params and unwraps the envelope', async () => {
    getSpy.mockResolvedValueOnce(envelope(totalsFixture));

    const { result } = renderHook(() => useSellerPayoutTotals(), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledTimes(1);
    expect(getSpy).toHaveBeenCalledWith('/seller/payouts/totals');
    expect(result.current.data).toEqual(totalsFixture);
  });

  it('stays idle when disabled (anonymous page guard)', async () => {
    const { result } = renderHook(() => useSellerPayoutTotals(false), {
      wrapper: wrapperFor(makeClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('propagates the failure shape so the page can render its error state', async () => {
    const failure = {
      isAxiosError: true,
      response: { status: 401, data: { code: 401, message: 'unauthorized', data: null } },
    };
    getSpy.mockRejectedValueOnce(failure);

    const { result } = renderHook(() => useSellerPayoutTotals(), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(result.current.error).toBe(failure);
    expect(result.current.data).toBeUndefined();
  });
});
