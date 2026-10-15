import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { itemKeys, type ApiResponse, type Page } from './items';
import { orderKeys } from './orders';

/**
 * One order dispute, mirroring the backend's DisputeDto (backlog #169).
 * `previousStatus` is the order status the dispute froze (PAID or
 * COMPLETED); `listingRestored` is null until a refund resolution
 * writes it — true when the listing went back to AVAILABLE, false when
 * it was already SOLD and stayed SOLD.
 */
export interface OrderDispute {
  id: number;
  orderId: number;
  buyerId: number;
  sellerId: number;
  reason: string;
  status: 'OPEN' | 'RESOLVED_REFUND' | 'RESOLVED_RELEASE';
  previousStatus: 'PAID' | 'COMPLETED';
  listingRestored: boolean | null;
  createdAt: string;
  resolvedAt: string | null;
}

/** Allowlisted dispute statuses (backend DisputeStatus enum, source of truth). */
export const DISPUTE_STATUSES = ['OPEN', 'RESOLVED_REFUND', 'RESOLVED_RELEASE'] as const;

/**
 * POST /api/orders/{id}/dispute — the buyer disputes a PAID or
 * COMPLETED order. Opening moves no money: the order freezes in
 * DISPUTED until an ADMIN resolves it. Opening while a dispute is
 * already open returns that dispute unchanged server-side, so a
 * double-click is safe. On success the orders and items subtrees are
 * invalidated — the order's chip flips to DISPUTED everywhere it
 * renders.
 */
export function useOpenDispute() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { orderId: number; reason: string }) =>
      api
        .post<ApiResponse<OrderDispute>>(`/orders/${input.orderId}/dispute`, {
          reason: input.reason,
        })
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: orderKeys.all });
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
}

/** Maps a failed dispute-open call to user-facing copy. */
export function describeDisputeError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 422) {
    if (/window/i.test(message)) {
      return 'The dispute window for this order has closed.';
    }
    if (/already disputed/i.test(message)) {
      return 'This order was already disputed and resolved.';
    }
    return 'Only a paid or completed order can be disputed.';
  }
  if (code === 400) {
    return 'Please describe the problem (up to 500 characters).';
  }
  if (code === 409) {
    return 'Someone just acted on this order — please refresh and try again.';
  }
  if (code === 403) {
    return 'Only the buyer of this order can dispute it.';
  }
  if (code === 401) {
    return 'Please log in to dispute this order.';
  }
  if (code === 404) {
    return 'This order no longer exists.';
  }
  return message || 'Something went wrong. Please try again.';
}

/**
 * Query keys for the ADMIN disputes subtree, sibling to the reports
 * subtree under ['admin', …] so invalidating one never refetches the
 * other.
 */
export const adminDisputeKeys = {
  all: ['admin', 'disputes'] as const,
  list: (page: number, status: string) =>
    [...adminDisputeKeys.all, 'list', page, status] as const,
};

/**
 * GET /api/admin/disputes — ADMIN-only (the backend's @PreAuthorize is
 * the real gate). Paginated, newest first; the status filter is omitted
 * entirely when unset. Callers must only pass allowlisted status
 * values — anything else is a backend 400.
 */
export function useAdminDisputes(page = 0, status = '', enabled = true, size = 20) {
  return useQuery({
    queryKey: adminDisputeKeys.list(page, status),
    queryFn: () =>
      api
        .get<ApiResponse<Page<OrderDispute>>>('/admin/disputes', {
          params: { page, size, ...(status ? { status } : {}) },
        })
        .then((res) => res.data.data),
    enabled,
  });
}

/**
 * POST /api/admin/disputes/{id}/resolve-refund — resolves a dispute by
 * refunding the order through the mock PSP (exactly once server-side;
 * repeating the same resolution is idempotent). On success the
 * disputes, orders and items subtrees are invalidated: the queue row,
 * the order's REFUNDED chip and the listing's restored AVAILABLE badge
 * all refetch.
 */
export function useResolveDisputeRefund() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      api
        .post<ApiResponse<OrderDispute>>(`/admin/disputes/${id}/resolve-refund`, {})
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminDisputeKeys.all });
      void queryClient.invalidateQueries({ queryKey: orderKeys.all });
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
}

/**
 * POST /api/admin/disputes/{id}/resolve-release — resolves a dispute by
 * releasing the order back to its pre-dispute status. Idempotent
 * server-side on an already-released dispute; a refunded one answers
 * 422. Invalidations mirror the refund mutation.
 */
export function useResolveDisputeRelease() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      api
        .post<ApiResponse<OrderDispute>>(`/admin/disputes/${id}/resolve-release`, {})
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminDisputeKeys.all });
      void queryClient.invalidateQueries({ queryKey: orderKeys.all });
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
}

/** Maps a failed ADMIN disputes call to user-facing copy. */
export function describeAdminDisputesError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 403) {
    return 'You are not authorized to view the dispute queue.';
  }
  if (code === 401) {
    return 'Please log in again to view the dispute queue.';
  }
  if (code === 400) {
    return 'That filter is not valid.';
  }
  if (code === 404) {
    return 'That dispute no longer exists.';
  }
  if (code === 422) {
    return 'That dispute has already been resolved the other way.';
  }
  if (code === 409) {
    return 'Someone just resolved this dispute — please refresh.';
  }
  return message || 'Something went wrong. Please try again.';
}
