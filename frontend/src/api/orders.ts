import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { itemKeys, type ApiResponse, type Page } from './items';

export interface Order {
  id: number;
  itemId: number;
  buyerId: number;
  // 'REFUNDED' landed on the backend with the mock refund endpoint and
  // 'DISPUTED' with the dispute flow (backlog #169); this union mirrors
  // the server-side OrderStatus enum.
  status: 'PENDING' | 'PAID' | 'COMPLETED' | 'CANCELLED' | 'REFUNDED' | 'DISPUTED';
  amountCents: number;
  createdAt: string;
  // Mock PSP references (backend backlog #114): `null` until the pay /
  // refund transition has happened, then `cap_mock_…` / `rfd_mock_…`.
  // Demo references a support/reconciliation flow would quote — never a
  // real processor receipt. The internal PSP idempotency keys are never
  // exposed by the backend and are not part of this shape.
  captureId: string | null;
  refundId: string | null;
  // Cumulative refunded amount in cents (backend backlog #210): 0 until
  // the first refund, between 0 and amountCents while the order is
  // PAID-but-partially-refunded, exactly amountCents once REFUNDED.
  // Optional here so pre-#210-shaped fixtures keep type-checking; the
  // backend always sends it.
  refundedAmountCents?: number;
  // Handover code (backend backlog #179): the buyer's 6-digit code is
  // present ONLY in the pay and regenerate responses (its one plaintext
  // disclosure — the backend stores just the hash, and every read DTO
  // omits the field entirely). Optional + nullable here so read-shaped
  // fixtures keep type-checking; never expect it on a fetched order.
  handoverCode?: string | null;
  // Generic last-payment-failure note (backend backlog #211): present
  // ONLY on buyer reads of a still-PENDING order whose latest payment
  // attempt was declined. Fixed generic wording from the backend — no
  // PSP internals, no token, no idempotency key. Absent everywhere else
  // (seller/admin reads omit the field entirely).
  lastPaymentFailure?: string | null;
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
  receipt: (id: number) => [...orderKeys.all, 'receipt', id] as const,
};

/**
 * GET /api/orders/{id}/receipt shape (backlog #180). A dedicated
 * field-by-field projection — never the order entity — tying order,
 * listing, amount, both parties and the mock PSP references together
 * for printing. `paid` is the explicit marker: a PENDING order's
 * receipt has `paid: false`, `paidAt: null` and `captureId: null`.
 * `paidAt`/`completedAt` are the real transition instants the backend
 * stamps (orders transitioned before that landed keep `null` — never
 * backfilled). captureId/refundId are demo references, not a real
 * processor receipt.
 */
export interface OrderReceipt {
  orderId: number;
  status: Order['status'];
  paid: boolean;
  itemId: number;
  itemTitle: string;
  amountCents: number;
  formattedPrice: string;
  buyerUsername: string;
  sellerUsername: string;
  createdAt: string;
  paidAt: string | null;
  completedAt: string | null;
  captureId: string | null;
  refundId: string | null;
}

/**
 * Maps a failed GET /api/orders/{id}/receipt to a user-facing message.
 * A 404 covers both unknown ids and non-parties (the backend never
 * leaks that someone else's order exists), so the copy stays generic.
 */
export function describeReceiptError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  if (code === 404) {
    return 'This receipt is not available.';
  }
  if (code === 401) {
    return 'Please log in to view this receipt.';
  }
  return response?.data?.message || 'Something went wrong. Please try again.';
}

/**
 * GET /api/orders/{id}/receipt — buyer, seller or ADMIN only. Fetched
 * lazily (`enabled` flips when the user opens the receipt view) so
 * listing pages make no extra fetch until the receipt is asked for.
 */
export function useOrderReceipt(id: number, enabled = true) {
  return useQuery({
    queryKey: orderKeys.receipt(id),
    queryFn: () =>
      api
        .get<ApiResponse<OrderReceipt>>(`/orders/${id}/receipt`)
        .then((res) => res.data.data),
    enabled,
  });
}

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

/** POST /api/orders with an explicit Idempotency-Key header. */
export function createOrderWithIdempotencyKey(itemId: number, idempotencyKey: string) {
  return api
    .post<ApiResponse<Order>>(
      '/orders',
      { itemId },
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )
    .then((res) => res.data.data);
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
    mutationFn: (itemId: number) => createOrderWithIdempotencyKey(itemId, newIdempotencyKey()),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
}

/**
 * POST /api/orders with a caller-supplied Idempotency-Key — the bundle
 * cart's checkout (backlog #207) derives one key per item from its
 * batch id, so a retried checkout replays instead of double-ordering.
 * Same invalidation discipline as useCreateOrder.
 */
export function useCreateOrderWithKey() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ itemId, idempotencyKey }: { itemId: number; idempotencyKey: string }) =>
      createOrderWithIdempotencyKey(itemId, idempotencyKey),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
      void queryClient.invalidateQueries({ queryKey: orderKeys.all });
    },
  });
}

/**
 * POST /api/orders/{id}/pay — mock capture (no real money moves). The server
 * makes this idempotent: paying an already-PAID order returns the order
 * unchanged, so no second capture can happen, and a double-click only
 * resolves one capture. On success every `orders` query refetches rather
 * than being patched: the order's status flips PENDING → PAID and local
 * cache surgery cannot know which paginated pages contain it. A fresh
 * capture's response is the one place the buyer's handover code is ever
 * disclosed (backlog #179) — callers surface it from the mutation result
 * before the refetch replaces it, because reads never carry it again.
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
  if (code === 400 || /handover/i.test(message)) {
    return 'That handover code is not right — ask the buyer for the 6-digit code from their order page and try again.';
  }
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
 * same transaction. Since backlog #179 a seller caller must present the
 * buyer's 6-digit handover code; a missing/wrong code is a 400 and
 * repeated failures lock the code server-side. Completing an
 * already-COMPLETED order is idempotent server-side, so a double-click
 * is safe. On success both subtrees are invalidated: the seller-orders
 * subtree hangs off `orderKeys.all`, and the listing subtree must
 * refetch so the SOLD badge shows.
 */
export function useCompleteOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ orderId, handoverCode }: { orderId: number; handoverCode?: string }) =>
      api
        .post<ApiResponse<Order>>(
          `/orders/${orderId}/complete`,
          handoverCode ? { handoverCode } : {},
        )
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: orderKeys.all });
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
}

/**
 * Maps a failed POST /api/orders/{id}/handover-code/regenerate to a
 * user-facing message: only the buyer can regenerate, and only while
 * the order is PAID.
 */
export function describeRegenerateHandoverError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 403) {
    return 'Only the buyer of this order can generate a handover code.';
  }
  if (code === 422) {
    return 'A handover code can only be generated while the order is paid.';
  }
  if (code === 401) {
    return 'Please log in to generate a handover code.';
  }
  if (code === 404) {
    return 'This order no longer exists.';
  }
  return message || 'Something went wrong. Please try again.';
}

/**
 * POST /api/orders/{id}/handover-code/regenerate — buyer-only (backlog
 * #179): mints a fresh handover code, invalidating the old one, and
 * returns the new plaintext in the response — its one disclosure, so
 * the caller must surface it immediately. The recovery path for a buyer
 * who lost the code shown at pay time (only its hash is stored, so the
 * old code can never be displayed again).
 */
export function useRegenerateHandoverCode() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (orderId: number) =>
      api
        .post<ApiResponse<Order>>(`/orders/${orderId}/handover-code/regenerate`, {})
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: orderKeys.all });
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
    if (/exceeds the remaining/i.test(message)) {
      return 'That amount is more than what is left to refund on this order.';
    }
    if (/must be positive/i.test(message)) {
      return 'Enter a refund amount greater than zero.';
    }
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
export type RefundInput = number | { orderId: number; amountCents?: number };

export function useRefundOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: RefundInput) => {
      // A bare order id (or an input without amountCents) posts the
      // pre-#210 empty body — the backend reads an absent amount as
      // "refund everything still refundable". A partial refund posts
      // {amountCents} (backlog #210).
      const orderId = typeof input === 'number' ? input : input.orderId;
      const amountCents = typeof input === 'number' ? undefined : input.amountCents;
      return api
        .post<ApiResponse<Order>>(
          `/orders/${orderId}/refund`,
          amountCents == null ? {} : { amountCents },
        )
        .then((res) => res.data.data);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: orderKeys.all });
      void queryClient.invalidateQueries({ queryKey: itemKeys.all });
    },
  });
}
