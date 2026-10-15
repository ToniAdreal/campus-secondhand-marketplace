import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { ApiResponse, Page } from './items';

/**
 * One price offer, mirroring the backend's OfferDto (backlog #168).
 * orderId is null until the seller accepts — acceptance is the only
 * outcome that creates an order, and it is created at priceCents (the
 * agreed price), never at the listing's asking price.
 */
export interface PriceOffer {
  id: number;
  itemId: number;
  buyerId: number;
  sellerId: number;
  priceCents: number;
  status: 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED' | 'WITHDRAWN';
  orderId: number | null;
  expiresAt: string;
  createdAt: string;
}

export const offerKeys = {
  all: ['offers'] as const,
  made: ['offers', 'made'] as const,
  received: ['offers', 'received'] as const,
};

/**
 * POST /api/items/{itemId}/offers — proposes a price as the signed-in
 * buyer. One live PENDING offer per buyer+listing server-side: a
 * second submit while one is live answers 409 (mapped below), it never
 * silently replaces the first. Success invalidates both offer lists —
 * the seller's inbox gains the row too.
 */
export function useCreateOffer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { itemId: number; priceCents: number }) =>
      api
        .post<ApiResponse<PriceOffer>>(`/items/${input.itemId}/offers`, {
          priceCents: input.priceCents,
        })
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: offerKeys.all });
    },
  });
}

/** GET /api/offers/made — the caller's own offers as a buyer, newest first. */
export function useMadeOffers(enabled = true) {
  return useQuery({
    queryKey: offerKeys.made,
    queryFn: () =>
      api
        .get<ApiResponse<Page<PriceOffer>>>('/offers/made')
        .then((res) => res.data.data),
    enabled,
  });
}

/** GET /api/offers/received — offers on the caller's own listings, newest first. */
export function useReceivedOffers(enabled = true) {
  return useQuery({
    queryKey: offerKeys.received,
    queryFn: () =>
      api
        .get<ApiResponse<Page<PriceOffer>>>('/offers/received')
        .then((res) => res.data.data),
    enabled,
  });
}

/**
 * POST /api/offers/{id}/accept — seller side. Creates the order at the
 * agreed price server-side; success invalidates the offer lists, the
 * seller-orders subtree and the items subtree (the listing flipped to
 * RESERVED), mirroring the order mutations' invalidation doctrine.
 */
export function useAcceptOffer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      api
        .post<ApiResponse<PriceOffer>>(`/offers/${id}/accept`, {})
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: offerKeys.all });
      void queryClient.invalidateQueries({ queryKey: ['orders'] });
      void queryClient.invalidateQueries({ queryKey: ['items'] });
    },
  });
}

/** POST /api/offers/{id}/decline — seller side; terminal, creates no order. */
export function useDeclineOffer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      api
        .post<ApiResponse<PriceOffer>>(`/offers/${id}/decline`, {})
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: offerKeys.all });
    },
  });
}

/** POST /api/offers/{id}/withdraw — buyer side; frees the pair for a fresh offer. */
export function useWithdrawOffer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      api
        .post<ApiResponse<PriceOffer>>(`/offers/${id}/withdraw`, {})
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: offerKeys.all });
    },
  });
}

/** Maps a failed offer call to user-facing copy. */
export function describeOfferError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 409) {
    return (
      message ||
      'That is no longer possible — the listing may have been sold, or you already have a pending offer on it.'
    );
  }
  if (code === 422) {
    return message || 'That offer can no longer be changed.';
  }
  if (code === 404) {
    return 'That offer or listing no longer exists.';
  }
  if (code === 401) {
    return 'Please log in to make an offer.';
  }
  if (code === 400) {
    return 'Please enter a price above ¥0.';
  }
  return message || 'Something went wrong. Please try again.';
}
