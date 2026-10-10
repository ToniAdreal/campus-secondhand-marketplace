import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { api } from './client';
import type { ApiResponse, Page } from './items';

/**
 * One row of the ADMIN user list, mirroring the backend's AdminUserDto
 * (GET /api/admin/users, backlog #105). The DTO is a deliberate projection —
 * a password hash never reaches the wire, so none exists here either.
 * `roles` are role names sorted alphabetically (stable wire shape).
 */
export interface AdminUser {
  id: number;
  username: string;
  email: string;
  roles: string[];
  disabled: boolean;
  createdAt: string;
}

/**
 * Query keys for the ADMIN users subtree. Everything hangs off
 * `['admin', 'users']` so a disable/enable success invalidates every
 * paginated / searched variant of the list with one prefix invalidation.
 */
export const adminUserKeys = {
  all: ['admin', 'users'] as const,
  list: (page: number, q: string) => [...adminUserKeys.all, 'list', page, q] as const,
};

/**
 * GET /api/admin/users — ADMIN-only (the backend's class-level
 * @PreAuthorize is the real gate; the SPA route/nav hiding is cosmetic).
 * Paginated, newest first; `q` is an optional case-insensitive substring
 * over username and email and is omitted entirely when blank, mirroring
 * the items list. Non-admin callers get the JSON 403 envelope and
 * anonymous callers 401 — both surface as Axios errors here.
 */
export function useAdminUsers(page = 0, q = '', enabled = true, size = 20) {
  return useQuery({
    queryKey: adminUserKeys.list(page, q),
    queryFn: () =>
      api
        .get<ApiResponse<Page<AdminUser>>>('/admin/users', {
          params: { page, size, ...(q.trim() !== '' ? { q: q.trim() } : {}) },
        })
        .then((res) => res.data.data),
    enabled,
  });
}

/**
 * POST /api/admin/users/{id}/disable — disables the account: login 401s,
 * live tokens are rejected, and every refresh family is revoked
 * server-side. Guarded server-side (self-disable and ADMIN-on-ADMIN are
 * 403, unknown id 404); the page additionally renders no disable button
 * for those rows, mirroring the guards. On success the users subtree is
 * invalidated so the list refetches the account's new state.
 */
export function useDisableAdminUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      api
        .post<ApiResponse<AdminUser>>(`/admin/users/${id}/disable`, {})
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminUserKeys.all });
    },
  });
}

/**
 * POST /api/admin/users/{id}/enable — re-enables a disabled account so it
 * can log in again with fresh tokens (pre-disable tokens stay dead).
 * Enabling has no role guard server-side (it is restorative). On success
 * the users subtree is invalidated, mirroring the disable mutation.
 */
export function useEnableAdminUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      api
        .post<ApiResponse<AdminUser>>(`/admin/users/${id}/enable`, {})
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminUserKeys.all });
    },
  });
}

/**
 * One row of the ADMIN audit-log read view, mirroring the backend's
 * AuditLogDto (GET /api/admin/audit-log, backlog #109). Enum fields are
 * their constant names — the same allowlisted values the read endpoint's
 * filters accept.
 */
export interface AuditLogEntry {
  id: number;
  actorUserId: number;
  action: string;
  targetType: string;
  targetId: number;
  createdAt: string;
}

/** Allowlisted AuditAction values (backend enum, source of truth). */
export const AUDIT_ACTIONS = [
  'PASSWORD_CHANGED',
  'PASSWORD_RESET_COMPLETED',
  'TOTP_ENABLED',
  'ORDER_COMPLETED',
  'ORDER_REFUNDED',
  'USER_DISABLED',
  'USER_ENABLED',
] as const;

/** Allowlisted AuditTargetType values (backend enum, source of truth). */
export const AUDIT_TARGET_TYPES = ['USER', 'ORDER'] as const;

export interface AuditLogFilters {
  actorUserId?: number | null;
  action?: string;
  targetType?: string;
  targetId?: number | null;
}

/**
 * Query keys for the ADMIN audit-log subtree, sibling to the users
 * subtree under ['admin', …] so invalidating one never refetches the other.
 */
export const adminAuditLogKeys = {
  all: ['admin', 'audit-log'] as const,
  list: (page: number, filters: AuditLogFilters) =>
    [
      ...adminAuditLogKeys.all,
      'list',
      page,
      filters.actorUserId ?? null,
      filters.action ?? '',
      filters.targetType ?? '',
      filters.targetId ?? null,
    ] as const,
};

/**
 * GET /api/admin/audit-log — ADMIN-only (backend @PreAuthorize is the
 * real gate). Paginated, newest first; every filter is optional and is
 * omitted entirely when unset, so the unfiltered request stays
 * byte-identical to a plain paginated list. Callers must only pass
 * allowlisted action/targetType values — anything else is a backend 400.
 */
export function useAdminAuditLog(
  page = 0,
  filters: AuditLogFilters = {},
  enabled = true,
  size = 20,
) {
  return useQuery({
    queryKey: adminAuditLogKeys.list(page, filters),
    queryFn: () =>
      api
        .get<ApiResponse<Page<AuditLogEntry>>>('/admin/audit-log', {
          params: {
            page,
            size,
            ...(filters.actorUserId != null ? { actorUserId: filters.actorUserId } : {}),
            ...(filters.action ? { action: filters.action } : {}),
            ...(filters.targetType ? { targetType: filters.targetType } : {}),
            ...(filters.targetId != null ? { targetId: filters.targetId } : {}),
          },
        })
        .then((res) => res.data.data),
    enabled,
  });
}

/** Maps a failed audit-log call to user-facing copy. */
export function describeAuditLogError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 403) {
    return 'You are not authorized to view the audit log.';
  }
  if (code === 401) {
    return 'Please log in again to view the audit log.';
  }
  if (code === 400) {
    return 'That filter is not valid.';
  }
  return message || 'Something went wrong. Please try again.';
}

/** True when the failure is the backend's 403 for a non-ADMIN caller. */
export function isForbidden(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 403;
}

/**
 * Maps a failed ADMIN users call to user-facing copy. The backend speaks
 * the {code,message,data} envelope; the known guard cases get plain
 * language and anything else passes the server message through.
 */
export function describeAdminUserError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 403) {
    if (/yourself/i.test(message)) {
      return 'You cannot disable your own account.';
    }
    if (/admin/i.test(message)) {
      return 'Admin accounts cannot be disabled here — they are managed out-of-band.';
    }
    return 'You are not authorized to do that.';
  }
  if (code === 401) {
    return 'Please log in again to manage users.';
  }
  if (code === 404) {
    return 'That user no longer exists.';
  }
  return message || 'Something went wrong. Please try again.';
}
