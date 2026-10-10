import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { itemKeys, type ApiResponse, type Page } from './items';

export interface Order {
  id: number;
  itemId: number;
  buyerId: number;
  // 'REFUNDED' landed on the backend with the mock refund endpoint; this
  // union mirrors the server-side OrderStatus enum.
  status: 'PENDING' | 'PAID' | 'COMPLETED' | 'CANCELLED' | 'REFUNDED';
  amountCents: number;
  createdAt: string;
}

/**
 * Query keys for the orders subtree. Everything hangs off `['orders']` so a
 * single prefix invalidation (e.g. after the mock capture) refetches both
 * the detail pages and the list pages.
 */
export const orderKeys = {
  all: ['orders'] as const,
  list: () => [...orderKeys.all, 'list'] as const,
  detail: (id: number) => [...orderKeys.all, 'detail', id] as const,
  /** Seller-side reads hang off their own key so the buyer and seller lists refetch independently. */
  sellerList: () => [...orderKeys.all, 'seller', 'list'] as const,
  sellerSummary: () => [...orderKeys.all, 'seller', 'summary'] as const,
};

/**
 * GET /api/seller/orders/summary shape (backlog #108). Gross sums the
 * orders' amountCents price snapshots for PAID + COMPLETED orders only —
 * REFUNDED orders are excluded server-side, so the header never has to
 * re-derive money rules. `currentMonth` is the current calendar month
 * in the server's local time zone (documented server scope).
 */
export interface SellerSalesSummary {
  totalOrders: number;
  pendingCount: number;
  paidCount: number;
  completedCount: number;
  cancelledCount: number;
  refundedCount: number;
  grossCents: number;
  currentMonth: {
    year: number;
    month: number;
    totalOrders: number;
    completedCount: number;
    grossCents: number;
  };
}

/**
 * GET /api/orders/{id} — the caller's own order (buyer), or any order (ADMIN).
 * Backed by the buyer order reads landed on the backend; the order
 * confirmation page reads through this hook.
 */
export function useOrder(id: number) {
  return useQuery({
    queryKey: orderKeys.detail(id),
    queryFn: () =>
      api.get<ApiResponse<Order>>(`/orders/${id}`).then((res) => res.data.data),
  });
}

/**
 * GET /api/orders — the caller's own orders, newest first, as an infinite
 * list. The backend defaults to size 20 sorted createdAt desc, id desc (two
 * orders can share a createdAt instant); the My-orders page stitches pages
 * behind a "Load more" button. New pages stop when the server says there
 * are no more (number+1 >= totalPages).
 */
export function useOrdersInfinite(size = 20, enabled = true) {
  return useInfiniteQuery({
    queryKey: orderKeys.list(),
    queryFn: ({ pageParam = 0 }) =>
      api
        .get<ApiResponse<Page<Order>>>('/orders', { params: { page: pageParam, size } })
        .then((res) => res.data.data),
    getNextPageParam: (lastPage) =>
      lastPage.number + 1 < lastPage.totalPages ? lastPage.number + 1 : undefined,
    enabled,
  });
}

/**
 * GET /api/seller/orders — orders buyers placed on the caller's listings,
 * newest first, as an infinite list (mirrors useOrdersInfinite). The
 * backend scopes strictly to the caller's sellerId, so an authenticated
 * user with no listings just gets empty pages — there is no "buyer" role
 * to deny, so no 403 for non-sellers on the list endpoint.
 */
/**
 * GET /api/seller/orders/summary — the seller's sales totals from ONE
 * query (backlog #108): orders / completed / gross, all-time plus the
 * current calendar month. A seller with no orders gets zeros from the
 * backend (never an error), mirroring the seller list. Lifecycle
 * mutations invalidate the whole orders subtree, so the summary
 * refetches after a complete/refund without any extra wiring here.
 */
export function useSellerSalesSummary(enabled = true) {
  return useQuery({
    queryKey: orderKeys.sellerSummary(),
    queryFn: () =>
      api
        .get<ApiResponse<SellerSalesSummary>>('/seller/orders/summary')
        .then((res) => res.data.data),
    enabled,
  });
}

export function useSellerOrdersInfinite(size = 20, enabled = true) {
  return useInfiniteQuery({
    queryKey: orderKeys.sellerList(),
    queryFn: ({ pageParam = 0 }) =>
      api
        .get<ApiResponse<Page<Order>>>('/seller/orders', { params: { page: pageParam, size } })
        .then((res) => res.data.data),
    getNextPageParam: (lastPage) =>
      lastPage.number + 1 < lastPage.totalPages ? lastPage.number + 1 : undefined,
    enabled,
  });
}

/**
 * Maps a failed POST /api/orders to a user-facing message. The backend speaks
 * the {code,message,data} envelope; we keep the raw server message for
 * unexpected cases but rewrite the known conflict cases into plain language
 * (the 409 "already has an active order" races a concurrent buyer — telling
 * the user "someone just bought it" is both accurate and kind).
 */
export function describeOrderError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 409 || /already has an active order|not available|purchased by someone else/i.test(message)) {
    return 'Someone just grabbed this item — it is no longer available.';
  }
  if (code === 422 || /own listing/i.test(message)) {
    return 'You cannot buy your own listing.';
  }
  if (code === 401) {
    return 'Please log in to buy this item.';
  }
  if (code === 404) {
    return 'This listing no longer exists.';
  }
  return message || 'Something went wrong. Please try again.';
}

/**
 * Maps a failed POST /api/orders/{id}/pay to a user-facing message. The
 * backend speaks the {code,message,data} envelope; paying an already-PAID
 * order is idempotent on the server (no second capture), so "success" there
 * just returns the order — this mapper only runs on real failures.
 */export function describePayError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 402 || /declined/i.test(message)) {
    return 'Your payment was declined — the order is still pending, please try a different payment method.';
  }
  if (code === 409 || /concurrent|version|optimistic/i.test(message)) {
    return 'Someone just acted on this order — please refresh and try again.';
  }
  if (code === 422 || /not in PENDING|can no longer|terminal/i.test(message)) {
    return 'This order can no longer be paid — it was cancelled, completed, or refunded.';
  }
  if (code === 403) {
    return 'Only the buyer of this order can pay for it.';
  }
  if (code === 401) {
    return 'Please log in to pay for this order.';
  }
  if (code === 404) {
    return 'This order no longer exists.';
  }
  return message || 'Something went wrong. Please try again.';
}

function newIdempotencyKey(): string {
  // The backend replays a retried request with the same Idempotency-Key
  // instead of double-creating an order; a fresh key makes each explicit
  // user click its own logical request.
  return crypto.randomUUID();
}

/**
 * POST /api/orders — buys a listing. On success the whole `items` subtree is
 * invalidated rather than patched: the purchased item's status flips
 * AVAILABLE → RESERVED (affects list badges, the detail page, and any
 * filtered list), and local cache surgery cannot know which filtered pages
 * contain the item.
 */
export function useCreateOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (itemId: number) =>
      api
        .post<ApiResponse<Order>>(
          '/orders',
          { itemId },
          { headers: { 'Idempotency-Key': newIdempotencyKey() } },
        )
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
}

/**
 * POST /api/orders/{id}/pay — mock capture (no real money moves). The server
 * makes this idempotent: paying an already-PAID order returns the order
 * unchanged, so no second capture can happen, and a double-click only
 * resolves one capture. On success every `orders` query refetches rather
 * than being patched: the order's status flips PENDING → PAID and local
 * cache surgery cannot know which paginated pages contain it.
 */
export function usePayOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (orderId: number) =>
      api.post<ApiResponse<Order>>(`/orders/${orderId}/pay`, {}).then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['orders'] });
    },
  });
}

/**
 * Maps a failed POST /api/orders/{id}/cancel to a user-facing message. The
 * backend speaks the {code,message,data} envelope; a 422 means the order is
 * no longer in a cancellable state (already PAID, COMPLETED, CANCELLED, or
 * REFUNDED) — the buyer then needs the mock payment refund path instead.
 */
export function describeCancelError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 422 || /cannot be cancelled|no longer cancellable|not in PENDING/i.test(message)) {
    return 'This order can no longer be cancelled — it was already paid, completed, or cancelled.';
  }
  if (code === 409 || /concurrent|version|optimistic/i.test(message)) {
    return 'Someone just acted on this order — please refresh and try again.';
  }
  if (code === 403) {
    return 'Only the buyer of this order can cancel it.';
  }
  if (code === 401) {
    return 'Please log in to cancel this order.';
  }
  if (code === 404) {
    return 'This order no longer exists.';
  }
  return message || 'Something went wrong. Please try again.';
}

/**
 * POST /api/orders/{id}/cancel — the buyer cancels a PENDING order. The
 * server flips the order to CANCELLED and the listing back to AVAILABLE in
 * the same transaction, so both subtrees are invalidated: the order detail
 * refetches (CANCELLED) and the listing detail/list badges go back to
 * AVAILABLE. Re-cancelling an already-CANCELLED order is idempotent server-
 * side, so a double-click is safe.
 */
export function useCancelOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (orderId: number) =>
      api.post<ApiResponse<Order>>(`/orders/${orderId}/cancel`, {}).then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: orderKeys.all });
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
}

/**
 * Maps a failed POST /api/orders/{id}/complete to a user-facing message.
 * The backend speaks the {code,message,data} envelope; a 422 means the
 * order is not PAID (it is still PENDING, or already terminal) — completing
 * an already-COMPLETED order is idempotent on the server and never reaches
 * this mapper.
 */
export function describeCompleteError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 422 || /completion is not allowed|not in PAID/i.test(message)) {
    if (/COMPLETED/i.test(message)) {
      return 'This order was already completed.';
    }
    return 'Only a paid order can be completed — this one is not paid yet, or is already finished.';
  }
  if (code === 409 || /concurrent|version|optimistic/i.test(message)) {
    return 'Someone just acted on this order — please refresh and try again.';
  }
  if (code === 403) {
    return 'Only the seller of this listing can complete the order.';
  }
  if (code === 401) {
    return 'Please log in to complete this order.';
  }
  if (code === 404) {
    return 'This order no longer exists.';
  }
  return message || 'Something went wrong. Please try again.';
}

/**
 * POST /api/orders/{id}/complete — the seller (or an ADMIN) confirms the
 * handoff: PAID → COMPLETED, and the listing flips RESERVED → SOLD in the
 * same transaction. Completing an already-COMPLETED order is idempotent
 * server-side, so a double-click is safe. On success both subtrees are
 * invalidated: the seller-orders subtree hangs off `orderKeys.all`, and
 * the listing subtree must refetch so the SOLD badge shows.
 */
export function useCompleteOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (orderId: number) =>
      api.post<ApiResponse<Order>>(`/orders/${orderId}/complete`, {}).then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: orderKeys.all });
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
}

/**
 * Maps a failed POST /api/orders/{id}/refund to a user-facing message. The
 * backend speaks the {code,message,data} envelope; a 422 means the order
 * is not PAID — refunding an already-REFUNDED order is idempotent on the
 * server (no second refund call) and never reaches this mapper.
 */
export function describeRefundError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 422 || /refund is not allowed|not in PAID/i.test(message)) {
    if (/REFUNDED/i.test(message)) {
      return 'This order was already refunded.';
    }
    if (/COMPLETED/i.test(message)) {
      return 'This order was already completed — it can no longer be refunded.';
    }
    return 'Only a paid order can be refunded — this one is not paid yet, or is already finished.';
  }
  if (code === 409 || /concurrent|version|optimistic/i.test(message)) {
    return 'Someone just acted on this order — please refresh and try again.';
  }
  if (code === 403) {
    return 'Only the seller of this listing can refund the order.';
  }
  if (code === 401) {
    return 'Please log in to refund this order.';
  }
  if (code === 404) {
    return 'This order no longer exists.';
  }
  return message || 'Something went wrong. Please try again.';
}

/**
 * POST /api/orders/{id}/refund — the seller (or an ADMIN) refunds a PAID
 * order through the mock PSP: PAID → REFUNDED, and the listing flips
 * RESERVED → AVAILABLE in the same transaction, so both subtrees are
 * invalidated on success (the seller-orders subtree hangs off
 * `orderKeys.all`). The buyer cannot self-refund — that is a deliberate
 * backend design decision, so this hook only belongs on seller surfaces.
 */
export function useRefundOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (orderId: number) =>
      api.post<ApiResponse<Order>>(`/orders/${orderId}/refund`, {}).then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: orderKeys.all });
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
}
