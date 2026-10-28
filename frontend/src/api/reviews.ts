import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { ApiResponse, Page } from './items';
import { orderKeys } from './orders';
import { sellerKeys } from './sellers';

/**
 * One seller review, mirroring the backend's SellerReviewDto (backlog
 * #175). `body` is null for a star-only review; `reviewerUsername` is
 * resolved server-side from the buyer id the row stores. Backlog #246
 * adds `sellerResponse`: the seller's public reply, null until the
 * seller answers — it rides this same shape, so the profile, the
 * review list and the account export all show the same reply.
 */
export interface SellerReview {
  id: number;
  orderId: number;
  buyerId: number;
  reviewerUsername: string | null;
  sellerId: number;
  rating: number;
  body: string | null;
  sellerResponse: string | null;
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

/**
 * PUT /api/reviews/{id}/response — the seller answers one review:
 * the first response and every edit are the same idempotent PUT
 * (backlog #246). One response per review is structural server-side
 * (a single column on the review row), so this call can never create
 * a second reply. On success the reviews subtree (the list the
 * profile renders) and the sellers subtree are invalidated; the
 * aggregate never moves, because it reads the rating column alone.
 */
export function useRespondToReview() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { reviewId: number; response: string }) =>
      api
        .put<ApiResponse<SellerReview>>(`/reviews/${input.reviewId}/response`, {
          response: input.response,
        })
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: reviewKeys.all });
      void queryClient.invalidateQueries({ queryKey: sellerKeys.all });
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

/** Maps a failed seller-response call to user-facing copy. */
export function describeReviewResponseError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 400) {
    return 'Please write a response up to 1000 characters.';
  }
  if (code === 403) {
    return 'Only the seller can respond to this review.';
  }
  if (code === 401) {
    return 'Please log in to respond.';
  }
  if (code === 404) {
    return 'This review no longer exists.';
  }
  return message || 'Something went wrong. Please try again.';
}
