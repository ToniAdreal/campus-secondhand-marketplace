import { useMutation, useQueryClient } from '@tanstack/react-query';
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
