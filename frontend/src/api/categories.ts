import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { api } from './client';
import type { ApiResponse } from './items';

export interface Category {
  id: number;
  name: string;
  slug: string;
}

/**
 * Query keys for the category taxonomy. The admin page and the
 * browse/create dropdowns share the one `['categories']` key on purpose:
 * a successful ADMIN write invalidates this key once and every consumer
 * refetches the same fresh list — there is no second admin-only cache
 * that could go stale behind the dropdowns.
 */
export const categoryKeys = {
  all: ['categories'] as const,
};

/**
 * GET /api/categories — the seeded listing taxonomy, in insertion order.
 * Powers the category dropdown on the create-listing form and the category
 * filter on the browse page. Requires a signed-in user, like every non-auth API.
 */
export function useCategories() {
  return useQuery({
    queryKey: categoryKeys.all,
    queryFn: () =>
      api.get<ApiResponse<Category[]>>('/categories').then((res) => res.data.data),
    staleTime: 10 * 60_000, // taxonomy changes rarely; don't refetch on every visit
  });
}

/**
 * GET /api/categories for the ADMIN category page (backlog #229) — the
 * same key and query function as useCategories, with an `enabled` gate so
 * a non-ADMIN session renders the page's bounce without firing a request
 * (the backend's @PreAuthorize stays the real gate; the SPA hiding is
 * cosmetic, mirroring the /admin/reports page).
 */
export function useAdminCategories(enabled = true) {
  return useQuery({
    queryKey: categoryKeys.all,
    queryFn: () =>
      api.get<ApiResponse<Category[]>>('/categories').then((res) => res.data.data),
    enabled,
  });
}

/** Payload for POST /api/categories (ADMIN only, backlog #70). */
export interface CategoryWriteInput {
  name: string;
  /** Optional: blank means "derive from the name" on create and "keep the
   * current slug" on rename — the backend's two different blank semantics,
   * so the wire payload omits a blank slug instead of sending it. */
  slug?: string;
}

/**
 * Builds the write payload, dropping a blank slug. Sending `slug: ''`
 * would be a lie in both directions: create would derive nothing useful
 * from an empty string, and rename would silently rewrite a slug sellers
 * already know — the backend only keeps the slug when it is omitted.
 */
function writePayload(input: CategoryWriteInput): { name: string; slug?: string } {
  const slug = input.slug?.trim();
  return slug ? { name: input.name, slug } : { name: input.name };
}

/**
 * POST /api/categories — ADMIN-only (backlog #70). On success the
 * taxonomy key is invalidated: the admin list refetches, and so does
 * every dropdown reading the same key. The server side of that freshness
 * is backlog #87 — CategoryService.createCategory carries @CacheEvict
 * (allEntries) on the categories cache, so the next GET serves the new
 * row, not the cached taxonomy; a rejected write evicts nothing.
 */
export function useCreateCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: CategoryWriteInput) =>
      api
        .post<ApiResponse<Category>>('/categories', writePayload(input))
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: categoryKeys.all });
    },
  });
}

/**
 * PATCH /api/categories/{id} — ADMIN-only rename (backlog #132). Same
 * invalidation contract as create: the #87 @CacheEvict on
 * updateCategory flushes the server cache, and this invalidation flushes
 * the SPA cache, so a rename reaches the dropdowns without a reload.
 */
export function useRenameCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...input }: CategoryWriteInput & { id: number }) =>
      api
        .patch<ApiResponse<Category>>(`/categories/${id}`, writePayload(input))
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: categoryKeys.all });
    },
  });
}

/**
 * DELETE /api/categories/{id} — ADMIN-only (backlog #70). 204 on
 * success; 409 while listings still reference the category (the page
 * maps that to the recategorize-first copy). Same invalidation contract
 * as create/rename: deleteCategory carries the #87 @CacheEvict.
 */
export function useDeleteCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.delete(`/categories/${id}`).then(() => undefined),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: categoryKeys.all });
    },
  });
}

/** True when the failure is the backend's 401 for an expired session. */
export function isUnauthorized(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 401;
}

/**
 * Maps a failed ADMIN category call to user-facing copy. The backend
 * speaks the {code,message,data} envelope; the known guard cases get
 * plain language and anything else passes the server message through.
 * The 409 copy depends on the operation: a save conflict is a duplicate
 * name/slug, but a delete conflict is a category still in use — the
 * honest fix is recategorizing the listings first, via the item-edit
 * flow, not retrying the delete.
 */
export function describeAdminCategoryError(
  error: unknown,
  kind: 'save' | 'delete',
): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 403) {
    return 'You are not authorized to manage categories.';
  }
  if (code === 401) {
    return 'Please log in again to manage categories.';
  }
  if (code === 404) {
    return 'That category no longer exists.';
  }
  if (code === 409) {
    return kind === 'delete'
      ? 'This category still has listings. Recategorize those listings first — edit each listing and pick another category — then delete it again.'
      : 'A category with that name or slug already exists.';
  }
  if (code === 400) {
    return message || 'That category is not valid.';
  }
  return message || 'Something went wrong. Please try again.';
}
