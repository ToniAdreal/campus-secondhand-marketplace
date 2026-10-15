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
  | 'OFFER_DECLINED';

export interface Notification {
  id: number;
  type: NotificationType;
  /** Null on OFFER_DECLINED rows — declining creates no order (#168). */
  orderId: number | null;
  /** The offer the row is about; null on pure order rows (#168). */
  offerId: number | null;
  createdAt: string;
  /** When the recipient marked it read; null while unread (#157). */
  readAt: string | null;
}

export const notificationKeys = {
  all: ['notifications'] as const,
  list: ['notifications', 'list'] as const,
  unreadCount: ['notifications', 'unread-count'] as const,
};

/** Human-readable line for one notification type. */
export function describeNotification(type: NotificationType): string {
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
