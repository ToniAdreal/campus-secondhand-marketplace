import { useQuery } from '@tanstack/react-query';
import { api } from './client';
import type { ApiResponse, Item, Page } from './items';

/**
 * Public seller profile (backlog #174) — mirrors the backend's
 * PublicSellerDto exactly: username, member-since, live-listing count
 * and completed-sales count, plus the review aggregate folded in by
 * backlog #175 (review count and the average rating computed from the
 * review rows on every read; `averageRating` is null until the seller
 * has a first review). There is deliberately no email or other account
 * field: the backend excludes them by construction.
 */
export interface SellerProfile {
  username: string;
  memberSince: string;
  availableListingCount: number;
  completedSalesCount: number;
  reviewCount: number;
  averageRating: number | null;
}

export const sellerKeys = {
  all: ['sellers'] as const,
  profile: (id: number) => [...sellerKeys.all, 'profile', id] as const,
  listings: (id: number) => [...sellerKeys.all, 'listings', id] as const,
};

/** GET /api/users/{id}/profile — 404 for unknown or disabled sellers. */
export function useSellerProfile(id: number) {
  return useQuery({
    queryKey: sellerKeys.profile(id),
    queryFn: () =>
      api
        .get<ApiResponse<SellerProfile>>(`/users/${id}/profile`)
        .then((res) => res.data.data),
    enabled: Number.isFinite(id) && id > 0,
  });
}

/** GET /api/users/{id}/listings — the seller's AVAILABLE listings only. */
export function useSellerListings(id: number) {
  return useQuery({
    queryKey: sellerKeys.listings(id),
    queryFn: () =>
      api
        .get<ApiResponse<Page<Item>>>(`/users/${id}/listings`, {
          params: { page: 0, size: 20 },
        })
        .then((res) => res.data.data),
    enabled: Number.isFinite(id) && id > 0,
  });
}
