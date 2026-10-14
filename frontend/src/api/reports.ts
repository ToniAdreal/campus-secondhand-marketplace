import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { ApiResponse, Page } from './items';

/**
 * One listing report, mirroring the backend's ListingReportDto
 * (backlog #160). Enum fields are their constant names — the same
 * allowlisted values the report endpoint accepts and the ADMIN queue's
 * status filter takes.
 */
export interface ListingReport {
  id: number;
  reporterId: number;
  itemId: number;
  reason: string;
  note: string | null;
  status: string;
  createdAt: string;
}

/** Allowlisted report reasons (backend ReportReason enum, source of truth). */
export const REPORT_REASONS = ['SPAM', 'SCAM', 'PROHIBITED', 'OTHER'] as const;

export type ReportReason = (typeof REPORT_REASONS)[number];

/** Allowlisted report statuses (backend ReportStatus enum, source of truth). */
export const REPORT_STATUSES = ['OPEN', 'DISMISSED', 'ACTIONED'] as const;

/**
 * POST /api/items/{itemId}/report — files a report as the signed-in
 * caller. The backend dedupes: a repeat while the caller's earlier
 * report is still OPEN returns that report instead of a second row, so
 * no cache surgery is needed here (nothing the SPA renders changes).
 * Reporting your own listing is 422 and creation is rate-limited per
 * user (429) — both map through describeReportError.
 */
export function useCreateReport() {
  return useMutation({
    mutationFn: (input: { itemId: number; reason: ReportReason; note?: string }) =>
      api
        .post<ApiResponse<ListingReport>>(`/items/${input.itemId}/report`, {
          reason: input.reason,
          ...(input.note !== undefined && input.note.trim() !== ''
            ? { note: input.note.trim() }
            : {}),
        })
        .then((res) => res.data.data),
  });
}

/** Maps a failed report call to user-facing copy. */
export function describeReportError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 422) {
    return 'You cannot report your own listing.';
  }
  if (code === 429) {
    return 'You are reporting too quickly — please wait a moment and try again.';
  }
  if (code === 401) {
    return 'Please log in to report this listing.';
  }
  if (code === 404) {
    return 'This listing no longer exists.';
  }
  if (code === 400) {
    return 'Please choose a reason for the report.';
  }
  return message || 'Something went wrong. Please try again.';
}

/**
 * Query keys for the ADMIN reports subtree, sibling to the users and
 * audit-log subtrees under ['admin', …] so invalidating one never
 * refetches the others.
 */
export const adminReportKeys = {
  all: ['admin', 'reports'] as const,
  list: (page: number, status: string) =>
    [...adminReportKeys.all, 'list', page, status] as const,
};

/**
 * GET /api/admin/reports — ADMIN-only (the backend's @PreAuthorize is
 * the real gate). Paginated, newest first; the status filter is omitted
 * entirely when unset, so the unfiltered request stays a plain
 * paginated list. Callers must only pass allowlisted status values —
 * anything else is a backend 400.
 */
export function useAdminReports(page = 0, status = '', enabled = true, size = 20) {
  return useQuery({
    queryKey: adminReportKeys.list(page, status),
    queryFn: () =>
      api
        .get<ApiResponse<Page<ListingReport>>>('/admin/reports', {
          params: { page, size, ...(status ? { status } : {}) },
        })
        .then((res) => res.data.data),
    enabled,
  });
}

/**
 * POST /api/admin/reports/{id}/dismiss — resolves a report without
 * acting on the listing. Idempotent server-side on an already-DISMISSED
 * report; an ACTIONED one answers 422. On success the reports subtree
 * is invalidated so the queue refetches the row's new state.
 */
export function useDismissReport() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      api
        .post<ApiResponse<ListingReport>>(`/admin/reports/${id}/dismiss`, {})
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminReportKeys.all });
    },
  });
}

/**
 * POST /api/admin/reports/{id}/action — resolves a report by
 * withdrawing the listing (AVAILABLE → WITHDRAWN server-side, in the
 * same transaction). Idempotent server-side on an already-ACTIONED
 * report; a DISMISSED one answers 422. On success the reports subtree
 * is invalidated, mirroring the dismiss mutation.
 */
export function useActionReport() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      api
        .post<ApiResponse<ListingReport>>(`/admin/reports/${id}/action`, {})
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminReportKeys.all });
    },
  });
}

/** Maps a failed ADMIN reports call to user-facing copy. */
export function describeAdminReportsError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 403) {
    return 'You are not authorized to view the moderation queue.';
  }
  if (code === 401) {
    return 'Please log in again to view the moderation queue.';
  }
  if (code === 400) {
    return 'That filter is not valid.';
  }
  if (code === 404) {
    return 'That report no longer exists.';
  }
  if (code === 422) {
    return 'That report has already been resolved the other way.';
  }
  return message || 'Something went wrong. Please try again.';
}
