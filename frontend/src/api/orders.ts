import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { itemKeys, type ApiResponse } from './items';

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
 * GET /api/orders/{id} — the caller's own order (buyer), or any order (ADMIN).
 * Backed by the buyer order reads landed on the backend; the order
 * confirmation page reads through this hook.
 */
export function useOrder(id: number) {
  return useQuery({
    queryKey: ['orders', id],
    queryFn: () =>
      api.get<ApiResponse<Order>>(`/orders/${id}`).then((res) => res.data.data),
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
 */
export function describePayError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
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
