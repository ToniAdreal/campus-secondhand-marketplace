import { useQuery } from '@tanstack/react-query';
import { api } from './client';
import type { ApiResponse, Item } from './items';

export const recentlyViewedKeys = {
  all: ['recently-viewed'] as const,
  list: ['recently-viewed', 'list'] as const,
};

/**
 * GET /api/users/me/recently-viewed — the caller's browsing trail
 * (backlog #195), most-recent view first, capped at 20 on the backend,
 * in the same list-view shape as GET /api/items. Only listings still
 * AVAILABLE ride along: a viewed listing that has since sold, expired
 * or been deleted is silently omitted by the backend, never rendered
 * as a tombstone. The trail is written by the detail read itself —
 * there is no client-side record call. Only enabled signed in —
 * anonymous callers would 401, and the SPA hides the strip for them
 * anyway. A malformed payload degrades to "no trail" instead of
 * crashing the home page.
 */
export function useRecentlyViewed(enabled = true) {
  return useQuery({
    queryKey: recentlyViewedKeys.list,
    queryFn: () =>
      api.get<ApiResponse<Item[]>>('/users/me/recently-viewed').then((res) => {
        const data = res.data.data;
        return Array.isArray(data) ? data : [];
      }),
    enabled,
  });
}
