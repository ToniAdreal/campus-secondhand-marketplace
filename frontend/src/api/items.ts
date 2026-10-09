import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';

export interface Item {
  id: number;
  title: string;
  description: string | null;
  priceCents: number;
  status: 'AVAILABLE' | 'RESERVED' | 'SOLD';
  sellerId: number;
  /** Resolved by the backend's read projections (join on the sellerId FK);
   * null when the seller row no longer exists. */
  sellerUsername: string | null;
  categoryId: number | null;
  categoryName: string | null;
  photoUrl: string | null;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export const itemKeys = {
  all: ['items'] as const,
  list: (page: number, q?: string, categoryId?: number | null) =>
    [...itemKeys.all, 'list', page, q ?? '', categoryId ?? null] as const,
  detail: (id: number) => [...itemKeys.all, 'detail', id] as const,
};

export function useItems(page = 0, q = '', categoryId?: number | null) {
  return useQuery({
    queryKey: itemKeys.list(page, q, categoryId),
    queryFn: () =>
      api
        .get<ApiResponse<Page<Item>>>('/items', {
          params: {
            page,
            size: 20,
            ...(q.trim() !== '' ? { q: q.trim() } : {}),
            ...(categoryId != null ? { categoryId } : {}),
          },
        })
        .then((res) => res.data.data),
  });
}

export function useItem(id: number) {
  return useQuery({
    queryKey: itemKeys.detail(id),
    queryFn: () =>
      api.get<ApiResponse<Item>>(`/items/${id}`).then((res) => res.data.data),
  });
}

export interface CreateListingInput {
  title: string;
  description?: string;
  priceCents: number;
  categoryId?: number | null;
}

/**
 * POST /api/items. On success the whole `items` subtree is invalidated
 * (every list page / search query and every detail view) instead of editing
 * the cache by hand — a new listing's position in paginated, filtered lists
 * cannot be computed locally, so an invalidation + refetch is the only
 * correct source of truth.
 */
export function useCreateListing() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateListingInput) =>
      api
        .post<ApiResponse<Item>>('/items', input)
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
}

/**
 * POST /api/items/{id}/photo (multipart). Axios sets the multipart
 * Content-Type + boundary itself from the FormData — no manual header.
 */
export function uploadItemPhoto(itemId: number, file: File): Promise<Item> {
  const form = new FormData();
  form.append('file', file, file.name);
  return api
    .post<ApiResponse<Item>>(`/items/${itemId}/photo`, form)
    .then((res) => res.data.data);
}

/**
 * Maps a failed PATCH /api/items/{id}/status to a user-facing message. The
 * backend speaks the {code,message,data} envelope; a 422 means the listing
 * is already SOLD (no onward transition exists) or the requested status was
 * not SOLD — this client only ever sends SOLD.
 */
export function describeMarkSoldError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 422 || /already SOLD|only the SOLD transition/i.test(message)) {
    return 'This listing is already sold — it can no longer be marked sold.';
  }
  if (code === 403) {
    return 'Only the seller (or an admin) can mark this listing sold.';
  }
  if (code === 401) {
    return 'Please log in to update this listing.';
  }
  if (code === 404) {
    return 'This listing no longer exists.';
  }
  return message || 'Something went wrong. Please try again.';
}

/**
 * PATCH /api/items/{id}/status — the seller (or an ADMIN) marks a listing
 * SOLD. SOLD is terminal on the backend (AVAILABLE/RESERVED → SOLD only), so
 * on success the whole `items` subtree is invalidated rather than patched:
 * the detail badge, the list rows, and any filtered pages refetch.
 */
export function useMarkSold() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (itemId: number) =>
      api
        .patch<ApiResponse<Item>>(`/items/${itemId}/status`, { status: 'SOLD' })
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
}
