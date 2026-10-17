import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { ApiResponse, Page } from './items';

export type NotificationType =
  | 'ORDER_PAID'
  | 'ORDER_COMPLETED'
  | 'ORDER_REFUNDED'
  | 'ORDER_CANCELLED'
  | 'ORDER_EXPIRED'
  | 'OFFER_ACCEPTED'
  | 'OFFER_DECLINED'
  | 'ORDER_DISPUTED'
  | 'DISPUTE_RESOLVED_REFUND'
  | 'DISPUTE_RESOLVED_RELEASE'
  | 'LOGIN_NEW_DEVICE'
  | 'SAVED_SEARCH_MATCH';

export interface Notification {
  id: number;
  type: NotificationType;
  /** Null on OFFER_DECLINED rows — declining creates no order (#168). */
  orderId: number | null;
  /** The offer the row is about; null on pure order rows (#168). */
  offerId: number | null;
  /** The saved search a SAVED_SEARCH_MATCH row names; null otherwise (#182). */
  savedSearchId: number | null;
  /** Batched new-match count on a SAVED_SEARCH_MATCH row; null otherwise (#182). */
  matchedListingCount: number | null;
  createdAt: string;
  /** When the recipient marked it read; null while unread (#157). */
  readAt: string | null;
}

export const notificationKeys = {
  all: ['notifications'] as const,
  list: ['notifications', 'list'] as const,
  unreadCount: ['notifications', 'unread-count'] as const,
};

/**
 * Human-readable line for one notification type. The optional match
 * count only matters for SAVED_SEARCH_MATCH (#182): the row is
 * batched, so its line says how many new listings matched.
 */
export function describeNotification(
  type: NotificationType,
  matchedListingCount?: number | null,
): string {
  switch (type) {
    case 'ORDER_PAID':
      return 'An order for your listing was paid.';
    case 'ORDER_COMPLETED':
      return 'Your order was completed.';
    case 'ORDER_REFUNDED':
      return 'Your order was refunded.';
    case 'ORDER_CANCELLED':
      return 'An order for your listing was cancelled.';
    case 'ORDER_EXPIRED':
      return 'Your order expired before payment.';
    case 'OFFER_ACCEPTED':
      return 'The seller accepted your offer — an order was created at your price.';
    case 'OFFER_DECLINED':
      return 'The seller declined your offer.';
    case 'ORDER_DISPUTED':
      return 'A buyer disputed an order for your listing.';
    case 'DISPUTE_RESOLVED_REFUND':
      return 'A dispute was resolved with a refund.';
    case 'DISPUTE_RESOLVED_RELEASE':
      return 'A dispute was resolved and the order was released.';
    case 'LOGIN_NEW_DEVICE':
      // Backlog #178: an alert, not a block — the sign-in succeeded.
      // The copy points at the remedy without claiming anything was stopped.
      return "New sign-in from a device or network you haven't used before. Wasn't you? Revoke your sessions and change your password.";
    case 'SAVED_SEARCH_MATCH': {
      // Backlog #182: one batched row per saved search per run — the
      // count is part of the message, never one row per listing.
      const count = matchedListingCount ?? 0;
      return count === 1
        ? '1 new listing matches your saved search.'
        : `${count} new listings match your saved search.`;
    }
    default:
      return 'Order update.';
  }
}

/**
 * GET /api/notifications — the caller's own notifications (backlog
 * #157), newest first, one page (the backend defaults to 20, the
 * global page-size cap applies). Only enabled signed in — anonymous
 * callers would 401.
 */
export function useNotifications(enabled = true) {
  return useQuery({
    queryKey: notificationKeys.list,
    queryFn: () =>
      api
        .get<ApiResponse<Page<Notification>>>('/notifications')
        .then((res) => res.data.data),
    enabled,
  });
}

/**
 * GET /api/notifications/unread-count — the caller's total unread
 * notifications, for the nav bell (#157). A bare count: the endpoint
 * exposes no notification or order data.
 */
export function useUnreadNotificationCount(enabled = true) {
  return useQuery({
    queryKey: notificationKeys.unreadCount,
    queryFn: () =>
      api
        .get<ApiResponse<number>>('/notifications/unread-count')
        .then((res) => res.data.data),
    enabled,
  });
}

/**
 * POST /api/notifications/read-all (backlog #173) — stamps every unread
 * row of the caller's in one call; the response is the stamped count.
 * Success invalidates the whole notifications subtree, so the bell
 * count refreshes from the server.
 */
export function useMarkAllNotificationsRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      api
        .post<ApiResponse<number>>('/notifications/read-all')
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: notificationKeys.all });
    },
  });
}

/**
 * DELETE /api/notifications/{id} (backlog #173) — removes one of the
 * caller's own rows (someone else's id is 404 server-side). Success
 * invalidates the list and the bell count.
 */
export function useDeleteNotification() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (notificationId: number) =>
      api.delete(`/notifications/${notificationId}`).then(() => undefined),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: notificationKeys.all });
    },
  });
}

/**
 * POST /api/notifications/{id}/read. Success invalidates the list and
 * the bell count — the read stamp comes from the server, never a local
 * cache hand-edit (same doctrine as #16).
 */
export function useMarkNotificationRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (notificationId: number) =>
      api
        .post<ApiResponse<Notification>>(`/notifications/${notificationId}/read`)
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: notificationKeys.all });
    },
  });
}
