import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { itemKeys, type ApiResponse, type Page } from './items';

/**
 * One report appeal, mirroring the backend's ReportAppealDto
 * (backlog #190). `listingRestored` is null until an uphold
 * resolution writes it — true when the listing went back to
 * AVAILABLE, false when it had moved on and was left untouched.
 */
export interface ReportAppeal {
  id: number;
  reportId: number;
  itemId: number;
  sellerId: number;
  reason: string;
  status: 'OPEN' | 'UPHELD' | 'REJECTED';
  listingRestored: boolean | null;
  createdAt: string;
  resolvedAt: string | null;
}

/** Allowlisted appeal statuses (backend ReportAppealStatus enum, source of truth). */
export const APPEAL_STATUSES = ['OPEN', 'UPHELD', 'REJECTED'] as const;

/**
 * One report on one of the caller's own listings, mirroring the
 * backend's SellerReportDto (backlog #190): the report plus the
 * caller's appeal state for it (all appeal fields null while no
 * appeal exists).
 */
export interface SellerReport {
  id: number;
  itemId: number;
  reason: string;
  status: 'OPEN' | 'DISMISSED' | 'ACTIONED';
  createdAt: string;
  appealId: number | null;
  appealStatus: 'OPEN' | 'UPHELD' | 'REJECTED' | null;
  appealListingRestored: boolean | null;
}

/** Query keys for the seller appeals/reports subtree. */
export const sellerAppealKeys = {
  all: ['seller', 'appeals'] as const,
  reports: ['seller', 'reports'] as const,
  appeals: ['seller', 'appeals', 'list'] as const,
};

/**
 * GET /api/seller/reports — reports filed against the caller's own
 * listings (the backend resolves the caller's listing ids server-side;
 * there is no way to ask for another seller's reports), each with its
 * appeal state. One page is enough for the My listings surface.
 */
export function useSellerReports(enabled = true) {
  return useQuery({
    queryKey: sellerAppealKeys.reports,
    queryFn: () =>
      api
        .get<ApiResponse<Page<SellerReport>>>('/seller/reports', {
          params: { page: 0, size: 100 },
        })
        .then((res) => res.data.data),
    enabled,
  });
}

/**
 * POST /api/seller/reports/{id}/appeal — the seller appeals an
 * ACTIONED report on their own listing. Filing while an appeal is
 * already open returns that appeal unchanged server-side, so a
 * double-click is safe. On success the seller subtree and the items
 * subtree are invalidated — an upheld appeal later flips the listing
 * back to AVAILABLE everywhere it renders.
 */
export function useCreateAppeal() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { reportId: number; reason: string }) =>
      api
        .post<ApiResponse<ReportAppeal>>(`/seller/reports/${input.reportId}/appeal`, {
          reason: input.reason,
        })
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: sellerAppealKeys.all });
      void queryClient.invalidateQueries({ queryKey: sellerAppealKeys.reports });
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
}

/** Maps a failed appeal call to user-facing copy. */
export function describeAppealError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 422) {
    if (/already appealed/i.test(message)) {
      return 'This report was already appealed and resolved.';
    }
    return 'Only a report that was actioned can be appealed.';
  }
  if (code === 400) {
    return 'Please explain why the decision should be reconsidered (up to 500 characters).';
  }
  if (code === 403) {
    return 'Only the seller of this listing can appeal its report.';
  }
  if (code === 401) {
    return 'Please log in to appeal this report.';
  }
  if (code === 404) {
    return 'This report no longer exists.';
  }
  if (code === 409) {
    return 'Someone just acted on this report — please refresh and try again.';
  }
  return message || 'Something went wrong. Please try again.';
}

/**
 * Query keys for the ADMIN appeals subtree, sibling to the disputes
 * and reports subtrees under ['admin', …] so invalidating one never
 * refetches the others.
 */
export const adminAppealKeys = {
  all: ['admin', 'appeals'] as const,
  list: (page: number, status: string) =>
    [...adminAppealKeys.all, 'list', page, status] as const,
};

/**
 * GET /api/admin/appeals — ADMIN-only (the backend's @PreAuthorize is
 * the real gate). Paginated, newest first; the status filter is
 * omitted entirely when unset. Callers must only pass allowlisted
 * status values — anything else is a backend 400.
 */
export function useAdminAppeals(page = 0, status = '', enabled = true, size = 20) {
  return useQuery({
    queryKey: adminAppealKeys.list(page, status),
    queryFn: () =>
      api
        .get<ApiResponse<Page<ReportAppeal>>>('/admin/appeals', {
          params: { page, size, ...(status ? { status } : {}) },
        })
        .then((res) => res.data.data),
    enabled,
  });
}

/**
 * POST /api/admin/appeals/{id}/uphold — resolves an appeal in the
 * seller's favour: the listing returns to AVAILABLE when it is still
 * WITHDRAWN; a listing that moved on is left untouched and the row
 * records which case applied. Idempotent server-side on an
 * already-upheld appeal; a rejected one answers 422.
 */
export function useUpholdAppeal() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      api
        .post<ApiResponse<ReportAppeal>>(`/admin/appeals/${id}/uphold`, {})
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminAppealKeys.all });
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
}

/**
 * POST /api/admin/appeals/{id}/reject — resolves an appeal against
 * the seller; the withdrawal stands. Idempotent server-side on an
 * already-rejected appeal; an upheld one answers 422.
 */
export function useRejectAppeal() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      api
        .post<ApiResponse<ReportAppeal>>(`/admin/appeals/${id}/reject`, {})
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminAppealKeys.all });
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
}

/** Maps a failed ADMIN appeals call to user-facing copy. */
export function describeAdminAppealsError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 403) {
    return 'You are not authorized to view the appeal queue.';
  }
  if (code === 401) {
    return 'Please log in again to view the appeal queue.';
  }
  if (code === 400) {
    return 'That filter is not valid.';
  }
  if (code === 404) {
    return 'That appeal no longer exists.';
  }
  if (code === 422) {
    return 'That appeal has already been resolved the other way.';
  }
  if (code === 409) {
    return 'Someone just resolved this appeal — please refresh.';
  }
  return message || 'Something went wrong. Please try again.';
}
