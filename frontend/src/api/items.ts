import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';

/** Mirrors the backend's ItemPhotoDto (backlog #69 gallery). */
export interface ItemPhoto {
  id: number;
  /** Public URL path ("/uploads/<uuid>.<ext>"), served without auth. */
  url: string;
  /** Display order within the listing's gallery; lowest is primary. */
  position: number;
}

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
  /**
   * The listing's photo gallery (backlog #69), ordered by position — the
   * first entry is the primary photo. Never null on the wire (the backend
   * sends an empty list); legacy listings uploaded through the single-photo
   * endpoint carry only {@link Item.photoUrl} and an empty gallery.
   */
  photos: ItemPhoto[];
}

/**
 * The photo URLs a listing renders, primary first. The gallery wins when
 * it has entries (sorted by position — the backend already orders them,
 * the sort keeps hand-built fixtures honest); a gallery-less listing falls
 * back to its legacy single {@link Item.photoUrl}, which is the channel
 * the create-listing form uploads through, so those photos show too.
 * Empty when the listing has no photo at all — callers render an honest
 * placeholder instead of a broken image. `/uploads/**` URLs are used
 * as-is: UploadWebConfig serves them publicly.
 */
export function listingPhotoUrls(item: Pick<Item, 'photos' | 'photoUrl'>): string[] {
  const gallery = [...(item.photos ?? [])]
    .sort((a, b) => a.position - b.position)
    .map((photo) => photo.url);
  if (gallery.length > 0) {
    return gallery;
  }
  return item.photoUrl ? [item.photoUrl] : [];
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

export interface ItemListFilters {
  minPriceCents?: number | null;
  maxPriceCents?: number | null;
  /** Allowlisted backend sort: 'newest' | 'price-asc' | 'price-desc'. */
  sort?: string | null;
}

export const itemKeys = {
  all: ['items'] as const,
  list: (page: number, q?: string, categoryId?: number | null, filters?: ItemListFilters) =>
    [
      ...itemKeys.all,
      'list',
      page,
      q ?? '',
      categoryId ?? null,
      filters?.minPriceCents ?? null,
      filters?.maxPriceCents ?? null,
      filters?.sort ?? null,
    ] as const,
  detail: (id: number) => [...itemKeys.all, 'detail', id] as const,
  mine: (page: number) => [...itemKeys.all, 'mine', page] as const,
};

export function useItems(
  page = 0,
  q = '',
  categoryId?: number | null,
  filters?: ItemListFilters,
) {
  return useQuery({
    // keepPreviousData (backlog #144): paging or re-filtering keeps the
    // previous page's rows on screen until the new page lands, so the
    // browse list never flashes empty between pages.
    keepPreviousData: true,
    queryKey: itemKeys.list(page, q, categoryId, filters),
    queryFn: () =>
      api
        .get<ApiResponse<Page<Item>>>('/items', {
          params: {
            page,
            size: 20,
            ...(q.trim() !== '' ? { q: q.trim() } : {}),
            ...(categoryId != null ? { categoryId } : {}),
            ...(filters?.minPriceCents != null
              ? { minPriceCents: filters.minPriceCents }
              : {}),
            ...(filters?.maxPriceCents != null
              ? { maxPriceCents: filters.maxPriceCents }
              : {}),
            ...(filters?.sort != null && filters.sort !== '' && filters.sort !== 'newest'
              ? { sort: filters.sort }
              : {}),
          },
        })
        .then((res) => res.data.data),
  });
}

/**
 * GET /api/items?mine=true — the caller's own listings (backlog #146),
 * newest first, every status included (RESERVED/SOLD rows are exactly
 * what a seller needs to find again). The backend resolves the seller
 * from the token; the client never sends a seller id. Only call this
 * signed in — anonymous callers get 401, and the page bounces them to
 * /login before the query matters.
 */
export function useMyListings(page = 0) {
  return useQuery({
    keepPreviousData: true,
    queryKey: itemKeys.mine(page),
    queryFn: () =>
      api
        .get<ApiResponse<Page<Item>>>('/items', {
          params: { page, size: 20, mine: true },
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
