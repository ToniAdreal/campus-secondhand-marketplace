import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { itemKeys, type ApiResponse, type Page } from './items';
import { orderKeys } from './orders';

/**
 * One order dispute, mirroring the backend's DisputeDto (backlog #169).
 * `previousStatus` is the order status the dispute froze (PAID or
 * COMPLETED); `listingRestored` is null until a refund resolution
 * writes it — true when the listing went back to AVAILABLE, false when
 * it was already SOLD and stayed SOLD. `resolutionNote` is the ADMIN's
 * reason for the verdict (backlog #240) — null while OPEN and on
 * pre-#240 resolutions, never blank once written.
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
  resolutionNote: string | null;
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

/**
 * One evidence photo on a dispute, mirroring the backend's
 * DisputeEvidenceDto (backlog #200). `url` is the public /uploads/
 * path of the stored file, served like a listing photo.
 */
export interface DisputeEvidence {
  id: number;
  disputeId: number;
  uploaderId: number;
  url: string;
  createdAt: string;
}

/** Query keys for the per-dispute evidence subtree. */
export const disputeEvidenceKeys = {
  all: ['disputes', 'evidence'] as const,
  list: (disputeId: number) => [...disputeEvidenceKeys.all, disputeId] as const,
  byOrder: (orderId: number) => ['disputes', 'by-order', orderId] as const,
};

/**
 * GET /api/orders/{id}/dispute — the dispute of one order, for its
 * buyer, seller or an ADMIN; everyone else (and an undisputed or
 * unknown order) gets the backend's 404. This is the discovery read
 * the evidence panel is keyed by.
 */
export function useOrderDispute(orderId: number, enabled = true) {
  return useQuery({
    queryKey: disputeEvidenceKeys.byOrder(orderId),
    queryFn: () =>
      api
        .get<ApiResponse<OrderDispute>>(`/orders/${orderId}/dispute`)
        .then((res) => res.data.data),
    enabled,
  });
}

/**
 * GET /api/disputes/{id}/evidence — the dispute's evidence photos,
 * oldest first. The backend answers 404 to anyone who is not the
 * buyer, the seller or an ADMIN.
 */
export function useDisputeEvidence(disputeId: number, enabled = true) {
  return useQuery({
    queryKey: disputeEvidenceKeys.list(disputeId),
    queryFn: () =>
      api
        .get<ApiResponse<DisputeEvidence[]>>(`/disputes/${disputeId}/evidence`)
        .then((res) => res.data.data),
    enabled,
  });
}

/**
 * POST /api/disputes/{id}/evidence (multipart) — attaches one photo
 * to an OPEN dispute. The backend storage gates apply unchanged
 * (allowlisted types, size cap, magic bytes), so a spoofed file is
 * the same 400 the listing-photo upload produces.
 */
export function useAddDisputeEvidence() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { disputeId: number; file: File }) => {
      const form = new FormData();
      form.append('file', input.file);
      return api
        .post<ApiResponse<DisputeEvidence>>(`/disputes/${input.disputeId}/evidence`, form)
        .then((res) => res.data.data);
    },
    onSuccess: (_data, input) => {
      void queryClient.invalidateQueries({
        queryKey: disputeEvidenceKeys.list(input.disputeId),
      });
    },
  });
}

/**
 * DELETE /api/disputes/{id}/evidence/{evidenceId} — removes one
 * photo; only its uploader (or an ADMIN) may, the other party gets
 * the backend's 403. The stored file is deleted best-effort
 * server-side.
 */
export function useRemoveDisputeEvidence() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { disputeId: number; evidenceId: number }) =>
      api
        .delete<ApiResponse<null>>(
          `/disputes/${input.disputeId}/evidence/${input.evidenceId}`,
        )
        .then((res) => res.data.data),
    onSuccess: (_data, input) => {
      void queryClient.invalidateQueries({
        queryKey: disputeEvidenceKeys.list(input.disputeId),
      });
    },
  });
}

/** Maps a failed dispute-evidence call to user-facing copy. */
export function describeDisputeEvidenceError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 400) {
    return 'That file could not be accepted — please attach a PNG, JPEG, WebP or GIF image within the size limit.';
  }
  if (code === 401) {
    return 'Please log in to manage dispute evidence.';
  }
  if (code === 403) {
    return 'Only the person who attached this photo can remove it.';
  }
  if (code === 404) {
    return 'This dispute no longer exists.';
  }
  if (code === 422) {
    return 'This dispute has been resolved — evidence is closed.';
  }
  return message || 'Something went wrong. Please try again.';
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
 * repeating the same resolution is idempotent, and the first
 * resolution's note stands). The ADMIN's resolution note is required
 * (backlog #240: blank/absent/over-1000 chars → 400) and travels in
 * the body, never as a query param. On success the disputes, orders
 * and items subtrees are invalidated: the queue row, the order's
 * REFUNDED chip and the listing's restored AVAILABLE badge all
 * refetch.
 */
export function useResolveDisputeRefund() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { id: number; resolutionNote: string }) =>
      api
        .post<ApiResponse<OrderDispute>>(`/admin/disputes/${input.id}/resolve-refund`, {
          resolutionNote: input.resolutionNote,
        })
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
 * server-side on an already-released dispute (the first note stands);
 * a refunded one answers 422. The resolution note is required, as for
 * the refund mutation. Invalidations mirror the refund mutation.
 */
export function useResolveDisputeRelease() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { id: number; resolutionNote: string }) =>
      api
        .post<ApiResponse<OrderDispute>>(`/admin/disputes/${input.id}/resolve-release`, {
          resolutionNote: input.resolutionNote,
        })
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
