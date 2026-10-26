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
  status: 'DRAFT' | 'AVAILABLE' | 'RESERVED' | 'SOLD' | 'WITHDRAWN' | 'EXPIRED';
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
  /** ISO-8601 creation timestamp (backend ItemDto). */
  createdAt?: string;
  /** ISO-8601 last-update timestamp (backend ItemDto). */
  updatedAt?: string;
  /**
   * Listing lifetime deadline (backlog #177): an AVAILABLE listing at
   * or past this instant is expired — gone from the public browse and
   * detail views, but still shown (flagged) in the seller's own
   * My-listings view, where it can be renewed. Null when the backend
   * row predates the deadline's backfill.
   */
  expiresAt?: string | null;
  /**
   * Detail-read view counter (backlog #186): present ONLY on the detail
   * response and on the seller's own My-listings rows — the backend
   * omits the key entirely on browse/storefront/favorites payloads, so
   * an absent value means "not a surface that shows views", never 0.
   */
  viewCount?: number | null;
}

/**
 * True when a listing's lifetime has run out at `now` (backlog #177):
 * the stored EXPIRED status, or an AVAILABLE listing past its
 * deadline that the nightly job has not flipped yet — the seller view
 * flags both, matching the backend's single isExpiredAt definition.
 */
export function isListingExpired(item: Pick<Item, 'status' | 'expiresAt'>, now = new Date()): boolean {
  if (item.status === 'EXPIRED') {
    return true;
  }
  return (
    item.status === 'AVAILABLE' &&
    item.expiresAt != null &&
    new Date(item.expiresAt).getTime() <= now.getTime()
  );
}

/** True when a live listing's deadline is close enough to renew (7-day window, mirroring the backend default). */
export function isListingExpiringSoon(item: Pick<Item, 'status' | 'expiresAt'>, now = new Date()): boolean {
  return (
    item.status === 'AVAILABLE' &&
    item.expiresAt != null &&
    !isListingExpired(item, now) &&
    new Date(item.expiresAt).getTime() <= now.getTime() + 7 * 24 * 60 * 60 * 1000
  );
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

/**
 * One point on a listing's price timeline (backlog #201), mirroring the
 * backend's PriceHistoryPointDto — exactly price + timestamp; the
 * endpoint deliberately carries no editor identity.
 */
export interface PriceHistoryPoint {
  priceCents: number;
  /** ISO-8601 instant this price took effect (creation for the first point). */
  changedAt: string;
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

/**
 * One search-suggestion row (backlog #235), mirroring the backend's
 * ItemSuggestionDto: exactly the listing id and its title. The dropdown
 * only displays the title, and selecting one navigates to the existing
 * {@code ?q=} results — no description, price or seller data rides along.
 */
export interface ItemSuggestion {
  id: number;
  title: string;
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
  suggestions: (q: string) => [...itemKeys.all, 'suggestions', q] as const,
  detail: (id: number) => [...itemKeys.all, 'detail', id] as const,
  priceHistory: (id: number) => [...itemKeys.all, 'price-history', id] as const,
  similar: (id: number) => [...itemKeys.all, 'similar', id] as const,
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

/**
 * GET /api/items/suggestions?q= — the search-suggestion dropdown feed
 * (backlog #235): title-prefix matches over the browseable set, capped
 * at 8 server-side, alphabetical. The query stays disabled while the
 * input is blank, so no request fires for an empty box, and the caller
 * gates it on a signed-in session — anonymous callers get 401 from the
 * chain, exactly like browse, so the dropdown never renders for them.
 * Debouncing lives with the caller (HomePage mirrors the #15 300 ms),
 * not here: this hook fetches exactly the query it is given.
 */
export function useItemSuggestions(q: string, enabled = true) {
  const trimmed = q.trim();
  return useQuery({
    queryKey: itemKeys.suggestions(trimmed),
    queryFn: () =>
      api
        .get<ApiResponse<ItemSuggestion[]>>('/items/suggestions', {
          params: { q: trimmed },
        })
        .then((res) => res.data.data),
    enabled: enabled && trimmed !== '',
  });
}

export function useItem(id: number) {
  return useQuery({
    queryKey: itemKeys.detail(id),
    queryFn: () =>
      api.get<ApiResponse<Item>>(`/items/${id}`).then((res) => res.data.data),
  });
}

/**
 * GET /api/items/{id}/price-history — the listing's price timeline
 * (backlog #201), oldest first: the creation price plus one point per
 * edit that actually changed the price. The detail page renders it as
 * a compact list under the current price and hides it when the read
 * fails (a withdrawn listing answers 404) or returns no points.
 */
export function usePriceHistory(id: number) {
  return useQuery({
    queryKey: itemKeys.priceHistory(id),
    queryFn: () =>
      api
        .get<ApiResponse<PriceHistoryPoint[]>>(`/items/${id}/price-history`)
        .then((res) => res.data.data),
  });
}

/**
 * GET /api/items/{id}/similar — the similar-listings rail (backlog
 * #202): AVAILABLE listings in the same category, in the backend's
 * pinned order (absolute price distance, then newest, then id),
 * capped server-side. A pure query — no recommendation engine. The
 * detail page renders the rail with the browse card and hides it when
 * the read fails or returns no listings.
 */
export function useSimilarItems(id: number) {
  return useQuery({
    queryKey: itemKeys.similar(id),
    queryFn: () =>
      api
        .get<ApiResponse<Item[]>>(`/items/${id}/similar`)
        .then((res) => res.data.data),
  });
}

export interface CreateListingInput {
  title: string;
  description?: string;
  priceCents: number;
  categoryId?: number | null;
  /**
   * Optional initial status (backlog #198): 'DRAFT' saves a
   * seller-private draft (no expiry clock until publish); absent or
   * 'AVAILABLE' keeps the historical publish-immediately behaviour.
   * The backend rejects any other value with 422.
   */
  status?: 'AVAILABLE' | 'DRAFT';
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
 * Maps a failed PUT /api/items/{id}/photos/order to a user-facing
 * message (backlog #162). The backend speaks the {code,message,data}
 * envelope; a 400 means the saved list was not an exact permutation
 * of the current gallery (the gallery changed under the editor), and
 * a 409 means a concurrent gallery edit won the @Version race.
 */
export function describeReorderPhotosError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 400 || /exactly once/i.test(message)) {
    return 'The gallery changed since this page loaded — reload and try again.';
  }
  if (code === 409) {
    return 'This gallery was just changed elsewhere — reload to see the latest order.';
  }
  if (code === 403) {
    return 'Only the seller (or an admin) can reorder these photos.';
  }
  if (code === 401) {
    return 'Please log in to reorder these photos.';
  }
  if (code === 404) {
    return 'This listing no longer exists.';
  }
  return message || 'Something went wrong. Please try again.';
}

/**
 * PUT /api/items/{id}/photos/order — saves the gallery's display order
 * (backlog #162). `photoIds` is the complete gallery in its new order;
 * the first id becomes the primary photo. On success the whole `items`
 * subtree is invalidated rather than patched: the primary photo feeds
 * browse cards and the detail gallery alike, and a refetch is the only
 * source of truth for position values.
 */
export function useReorderPhotos() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { itemId: number; photoIds: number[] }) =>
      api
        .put<ApiResponse<Item>>(`/items/${input.itemId}/photos/order`, {
          photoIds: input.photoIds,
        })
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
}

/**
 * Maps a failed POST /api/items/{id}/renew to a user-facing message
 * (backlog #177). The backend speaks the {code,message,data} envelope;
 * a 422 means the listing is not expired or expiring (a live listing
 * with a distant deadline, or a RESERVED/SOLD/WITHDRAWN one).
 */
export function describeRenewError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 422) {
    return 'This listing cannot be renewed yet — only expired or expiring listings can.';
  }
  if (code === 403) {
    return 'Only the seller (or an admin) can renew this listing.';
  }
  if (code === 401) {
    return 'Please log in to renew this listing.';
  }
  if (code === 404) {
    return 'This listing no longer exists.';
  }
  return message || 'Something went wrong. Please try again.';
}

/**
 * POST /api/items/{id}/renew — resets the listing's expiry deadline to
 * now + the backend TTL and returns an EXPIRED listing to AVAILABLE
 * (backlog #177). On success the whole `items` subtree is invalidated:
 * the renewed listing re-enters the browse lists and its seller view
 * row changes status, neither of which can be patched locally with
 * certainty.
 */
export function useRenewListing() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (itemId: number) =>
      api
        .post<ApiResponse<Item>>(`/items/${itemId}/renew`)
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
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

/**
 * Maps a failed POST /api/items/{id}/publish to a user-facing message
 * (backlog #198). The backend speaks the {code,message,data} envelope;
 * a 422 means the listing is not a draft.
 */
export function describePublishError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 422) {
    return 'Only a draft listing can be published.';
  }
  if (code === 403) {
    return 'Only the seller (or an admin) can publish this listing.';
  }
  if (code === 401) {
    return 'Please log in to publish this listing.';
  }
  if (code === 404) {
    return 'This listing no longer exists.';
  }
  return message || 'Something went wrong. Please try again.';
}

/** Plain POST helper for POST /api/items/{id}/publish (backlog #198). */
export function publishListing(itemId: number): Promise<Item> {
  return api
    .post<ApiResponse<Item>>(`/items/${itemId}/publish`)
    .then((res) => res.data.data);
}

/**
 * POST /api/items/{id}/publish — flips a DRAFT listing to AVAILABLE and
 * starts its expiry clock at publish time (backlog #198). On success the
 * whole `items` subtree is invalidated: the listing enters the public
 * browse lists and its My-listings row changes status.
 */
export function usePublishListing() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (itemId: number) => publishListing(itemId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
}
