import { useQuery } from '@tanstack/react-query';
import { api } from './client';
import type { ApiResponse } from './items';

export interface Category {
  id: number;
  name: string;
  slug: string;
}

/**
 * GET /api/categories — the seeded listing taxonomy, in insertion order.
 * Powers the category dropdown on the create-listing form and the category
 * filter on the browse page. Requires a signed-in user, like every non-auth API.
 */
export function useCategories() {
  return useQuery({
    queryKey: ['categories'],
    queryFn: () =>
      api.get<ApiResponse<Category[]>>('/categories').then((res) => res.data.data),
    staleTime: 10 * 60_000, // taxonomy changes rarely; don't refetch on every visit
  });
}
