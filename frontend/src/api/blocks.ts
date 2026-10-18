import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { ApiResponse } from './items';

/**
 * User-to-user blocking (backlog #187) — mirrors the backend's
 * BlockedUserDto: the blocked user's id and public username, plus when
 * the block was created. There is deliberately no email or other
 * account field: the backend excludes them by construction.
 */
export interface BlockedUser {
  blockedId: number;
  blockedUsername: string;
  createdAt: string;
}

export const blockKeys = {
  all: ['blocks'] as const,
  list: ['blocks', 'list'] as const,
};

/**
 * GET /api/users/me/blocks — the caller's blocked users, newest block
 * first. Only enabled signed in — anonymous callers would 401, and the
 * SPA hides the affordances for them anyway. A malformed payload
 * degrades to "no blocks" instead of crashing the surfaces.
 */
export function useMyBlocks(enabled = true) {
  return useQuery({
    queryKey: blockKeys.list,
    queryFn: () =>
      api.get<ApiResponse<BlockedUser[]>>('/users/me/blocks').then((res) => {
        const data = res.data.data;
        return Array.isArray(data) ? data : [];
      }),
    enabled,
  });
}

/** POST /api/users/{id}/block — idempotent on the backend (re-blocking is a no-op 200). */
export function useBlockUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (userId: number) => api.post(`/users/${userId}/block`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: blockKeys.all });
      void queryClient.invalidateQueries({ queryKey: ['items'] });
    },
  });
}

/** DELETE /api/users/{id}/block — idempotent on the backend (unblocking a non-blocked user is a no-op 200). */
export function useUnblockUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (userId: number) => api.delete(`/users/${userId}/block`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: blockKeys.all });
      void queryClient.invalidateQueries({ queryKey: ['items'] });
    },
  });
}
