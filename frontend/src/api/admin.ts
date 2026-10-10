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
