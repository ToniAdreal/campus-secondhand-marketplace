import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { ApiResponse, Item } from './items';

export const favoriteKeys = {
  all: ['favorites'] as const,
  list: ['favorites', 'list'] as const,
};

/**
 * GET /api/users/me/favorites — the caller's saved listings (backlog
 * #143), newest save first, in the same list-view shape as GET
 * /api/items (status included: a saved SOLD/RESERVED listing stays
 * listed with its badge). Only enabled signed in — anonymous callers
 * would 401, and the SPA hides the affordance for them anyway. A
 * malformed payload degrades to "no favorites" instead of crashing
 * the listing grid.
 */
export function useFavorites(enabled = true) {
  return useQuery({
    queryKey: favoriteKeys.list,
    queryFn: () =>
      api.get<ApiResponse<Item[]>>('/users/me/favorites').then((res) => {
        const data = res.data.data;
        return Array.isArray(data) ? data : [];
      }),
    enabled,
  });
}

/**
 * PUT/DELETE /api/users/me/favorites/{itemId}. Both writes are
 * idempotent on the backend (re-save and unsave-of-unsaved are no-op
 * 200s), so the toggle needs no client-side guard against double
 * clicks beyond the mutation's own pending state. Success invalidates
 * the favorites list — the heart state everywhere derives from it.
 */
export function useToggleFavorite() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ itemId, saved }: { itemId: number; saved: boolean }) =>
      saved
        ? api.delete(`/users/me/favorites/${itemId}`)
        : api.put(`/users/me/favorites/${itemId}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: favoriteKeys.all });
    },
  });
}
