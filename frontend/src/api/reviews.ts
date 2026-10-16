import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { ApiResponse, Page } from './items';
import { orderKeys } from './orders';
import { sellerKeys } from './sellers';

/**
 * One seller review, mirroring the backend's SellerReviewDto (backlog
 * #175). `body` is null for a star-only review; `reviewerUsername` is
 * resolved server-side from the buyer id the row stores.
 */
export interface SellerReview {
  id: number;
  orderId: number;
  buyerId: number;
  reviewerUsername: string | null;
  sellerId: number;
  rating: number;
  body: string | null;
  createdAt: string;
}

export const reviewKeys = {
  all: ['reviews'] as const,
  forSeller: (sellerId: number) => [...reviewKeys.all, 'seller', sellerId] as const,
};

/**
 * POST /api/orders/{id}/review — the buyer reviews a COMPLETED order:
 * one review per trade (a second attempt answers 409), buyer only.
 * Reviews are append-only in v1 — there is no edit or delete call.
 * On success the orders subtree (the row's state) and the sellers
 * subtree (profile aggregate + review list) are invalidated.
 */
export function useCreateReview() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { orderId: number; rating: number; body?: string }) =>
      api
        .post<ApiResponse<SellerReview>>(`/orders/${input.orderId}/review`, {
          rating: input.rating,
          ...(input.body ? { body: input.body } : {}),
        })
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: orderKeys.all });
      void queryClient.invalidateQueries({ queryKey: sellerKeys.all });
      void queryClient.invalidateQueries({ queryKey: reviewKeys.all });
    },
  });
}

/** GET /api/users/{id}/reviews — the seller's reviews, newest first. */
export function useSellerReviews(id: number) {
  return useQuery({
    queryKey: reviewKeys.forSeller(id),
    queryFn: () =>
      api
        .get<ApiResponse<Page<SellerReview>>>(`/users/${id}/reviews`, {
          params: { page: 0, size: 20 },
        })
        .then((res) => res.data.data),
    enabled: Number.isFinite(id) && id > 0,
  });
}

/** Maps a failed review-create call to user-facing copy. */
export function describeReviewError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 409) {
    return 'You have already reviewed this order.';
  }
  if (code === 422) {
    return 'Only a completed order can be reviewed.';
  }
  if (code === 400) {
    return 'Please pick a rating from 1 to 5 (text up to 500 characters).';
  }
  if (code === 403) {
    return 'Only the buyer of this order can review it.';
  }
  if (code === 401) {
    return 'Please log in to leave a review.';
  }
  if (code === 404) {
    return 'This order no longer exists.';
  }
  return message || 'Something went wrong. Please try again.';
}
