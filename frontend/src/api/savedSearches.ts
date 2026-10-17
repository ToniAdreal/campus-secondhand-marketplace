import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { ApiResponse } from './items';

/**
 * One saved search (backlog #164), mirroring the backend's
 * SavedSearchDto: the discrete browse filter set — never a serialized
 * query string — plus `matchCount`, how many listings the browse page
 * returns for exactly these filters right now (computed by the backend
 * on read through the browse projection itself). Background matching
 * (backlog #182) alerts through in-app notifications only — still no
 * email alert — and its watermark is never part of this shape.
 */
export interface SavedSearch {
  id: number;
  keyword: string | null;
  categoryId: number | null;
  minPriceCents: number | null;
  maxPriceCents: number | null;
  /** Allowlisted backend sort: 'newest' | 'price-asc' | 'price-desc'. */
  sort: string;
  matchCount: number;
  createdAt: string;
}

/** The filter set a save sends — the same shape the browse page holds. */
export interface SavedSearchInput {
  keyword?: string | null;
  categoryId?: number | null;
  minPriceCents?: number | null;
  maxPriceCents?: number | null;
  sort?: string | null;
}

export const savedSearchKeys = {
  all: ['saved-searches'] as const,
  list: ['saved-searches', 'list'] as const,
};

/**
 * GET /api/users/me/saved-searches — the caller's saved searches,
 * newest first, each with its live matchCount. Only enabled signed
 * in — anonymous callers would 401, and the SPA hides the affordances
 * for them anyway. A malformed payload degrades to "no saved
 * searches" instead of crashing the page.
 */
export function useSavedSearches(enabled = true) {
  return useQuery({
    queryKey: savedSearchKeys.list,
    queryFn: () =>
      api.get<ApiResponse<SavedSearch[]>>('/users/me/saved-searches').then((res) => {
        const data = res.data.data;
        return Array.isArray(data) ? data : [];
      }),
    enabled,
  });
}

/**
 * POST /api/users/me/saved-searches. The backend reuses the browse
 * validation (a negative bound, min > max, or an unknown sort is a
 * 400) and caps each user at 20 saved searches (422 beyond). Success
 * invalidates the saved-searches list so the favorites-page section
 * refetches with fresh match counts.
 */
export function useCreateSavedSearch() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: SavedSearchInput) =>
      api
        .post<ApiResponse<SavedSearch>>('/users/me/saved-searches', {
          keyword: input.keyword ?? null,
          categoryId: input.categoryId ?? null,
          minPriceCents: input.minPriceCents ?? null,
          maxPriceCents: input.maxPriceCents ?? null,
          sort: input.sort ?? null,
        })
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: savedSearchKeys.all });
    },
  });
}

/**
 * DELETE /api/users/me/saved-searches/{id} — own rows only; someone
 * else's id is a 404 on the backend (no existence leak). Success
 * invalidates the saved-searches list.
 */
export function useDeleteSavedSearch() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.delete(`/users/me/saved-searches/${id}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: savedSearchKeys.all });
    },
  });
}

/** Formats cents as the yuan string the browse URL carries (`?minPrice=`/`?maxPrice=`). */
function centsToYuanParam(cents: number): string {
  return String(Number((cents / 100).toFixed(2)));
}

/**
 * The browse URL that restores a saved search exactly: HomePage's own
 * parameter names — `?q=`, `?categoryId=`, `?minPrice=`/`?maxPrice=`
 * (yuan, as typed into the price inputs — converted here from the
 * stored cents), and `?sort=` (omitted for the 'newest' default,
 * exactly as HomePage's own sort control omits it). No `?page=`:
 * a restored search restarts at page 0.
 */
export function savedSearchBrowsePath(search: SavedSearch): string {
  const params = new URLSearchParams();
  if (search.keyword) {
    params.set('q', search.keyword);
  }
  if (search.categoryId != null) {
    params.set('categoryId', String(search.categoryId));
  }
  if (search.minPriceCents != null) {
    params.set('minPrice', centsToYuanParam(search.minPriceCents));
  }
  if (search.maxPriceCents != null) {
    params.set('maxPrice', centsToYuanParam(search.maxPriceCents));
  }
  if (search.sort && search.sort !== 'newest') {
    params.set('sort', search.sort);
  }
  const query = params.toString();
  return query === '' ? '/' : `/?${query}`;
}

/** A one-line human summary of a saved search's filter set, for the list rows. */
export function describeSavedSearch(search: SavedSearch): string {
  const parts: string[] = [];
  if (search.keyword) {
    parts.push(`"${search.keyword}"`);
  }
  if (search.categoryId != null) {
    parts.push(`Category #${search.categoryId}`);
  }
  if (search.minPriceCents != null) {
    parts.push(`from ¥${centsToYuanParam(search.minPriceCents)}`);
  }
  if (search.maxPriceCents != null) {
    parts.push(`up to ¥${centsToYuanParam(search.maxPriceCents)}`);
  }
  if (search.sort === 'price-asc') {
    parts.push('Price: low to high');
  } else if (search.sort === 'price-desc') {
    parts.push('Price: high to low');
  }
  return parts.length > 0 ? parts.join(' · ') : 'All listings';
}
