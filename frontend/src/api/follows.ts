import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { ApiResponse } from './items';

/**
 * Following a seller (backlog #217) — mirrors the backend's
 * FollowedSellerDto: the followed seller's id and public username, plus
 * when the follow was created. There is deliberately no email or other
 * account field: the backend excludes them by construction. The follow
 * is a notification tool, not a social graph — no follower counts exist
 * anywhere.
 */
export interface FollowedSeller {
  sellerId: number;
  sellerUsername: string;
  createdAt: string;
}

export const followKeys = {
  all: ['follows'] as const,
  list: ['follows', 'list'] as const,
};

/**
 * GET /api/users/me/follows — the caller's followed sellers, newest
 * follow first. Only enabled signed in — anonymous callers would 401,
 * and the SPA hides the affordances for them anyway. A malformed
 * payload degrades to "no follows" instead of crashing the surfaces.
 */
export function useMyFollows(enabled = true) {
  return useQuery({
    queryKey: followKeys.list,
    queryFn: () =>
      api.get<ApiResponse<FollowedSeller[]>>('/users/me/follows').then((res) => {
        const data = res.data.data;
        return Array.isArray(data) ? data : [];
      }),
    enabled,
  });
}

/** POST /api/users/me/follows/{sellerId} — idempotent on the backend (re-following is a no-op 200). */
export function useFollowSeller() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (sellerId: number) => api.post(`/users/me/follows/${sellerId}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: followKeys.all });
    },
  });
}

/** DELETE /api/users/me/follows/{sellerId} — idempotent on the backend (unfollowing a non-followed seller is a no-op 200). */
export function useUnfollowSeller() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (sellerId: number) => api.delete(`/users/me/follows/${sellerId}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: followKeys.all });
    },
  });
}
