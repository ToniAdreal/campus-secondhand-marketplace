import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { api } from './client';
import { type ApiResponse, type Page } from './items';

/**
 * GET /api/seller/payouts shape (backend backlog #221). One row per
 * completed order: the settlement figure from completion, the
 * compensating adjustments (negative or zero — a dispute refund after
 * completion never mutates the settlement row), and the net the seller
 * is actually owed on that order.
 */
export interface SellerPayout {
  id: number;
  orderId: number;
  sellerId: number;
  amountCents: number;
  adjustmentsCents: number;
  netCents: number;
  status: 'PENDING' | 'PAYABLE' | 'PAID';
  createdAt: string;
  payableAt: string | null;
  paidAt: string | null;
}

/** GET /api/seller/payouts/totals shape (backend backlog #221). */
export interface SellerPayoutTotals {
  pendingCount: number;
  pendingCents: number;
  payableCount: number;
  payableCents: number;
  paidCount: number;
  paidCents: number;
  adjustmentsCents: number;
  netCents: number;
}

/**
 * Query keys for the seller payout subtree. They hang off `['orders',
 * 'seller']` — not `['orders']` alone — so a payout refetch never
 * drags the buyer/seller order lists with it, and vice versa.
 */
export const payoutKeys = {
  list: () => ['orders', 'seller', 'payouts', 'list'] as const,
  totals: () => ['orders', 'seller', 'payouts', 'totals'] as const,
};

/** GET /api/seller/payouts — the seller's payout rows, newest first. */
export function useSellerPayoutsInfinite(size = 20, enabled = true) {
  return useInfiniteQuery({
    queryKey: payoutKeys.list(),
    queryFn: ({ pageParam = 0 }) =>
      api
        .get<ApiResponse<Page<SellerPayout>>>('/seller/payouts', { params: { page: pageParam, size } })
        .then((res) => res.data.data),
    getNextPageParam: (lastPage) =>
      lastPage.number + 1 < lastPage.totalPages ? lastPage.number + 1 : undefined,
    enabled,
  });
}

/** GET /api/seller/payouts/totals — per-status counts/sums plus the net. */
export function useSellerPayoutTotals(enabled = true) {
  return useQuery({
    queryKey: payoutKeys.totals(),
    queryFn: () =>
      api.get<ApiResponse<SellerPayoutTotals>>('/seller/payouts/totals').then((res) => res.data.data),
    enabled,
  });
}
