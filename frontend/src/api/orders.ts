import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import { itemKeys, type ApiResponse } from './items';

export interface Order {
  id: number;
  itemId: number;
  buyerId: number;
  status: 'PENDING' | 'PAID' | 'COMPLETED' | 'CANCELLED';
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
