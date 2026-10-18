import { Link, Navigate, useLocation } from 'react-router-dom';
import {
  describeNotification,
  useDeleteNotification,
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotifications,
  useUnreadNotificationCount,
} from '../api/notifications';
import { savedSearchBrowsePath, useSavedSearches } from '../api/savedSearches';
import { useAuthStore } from '../store/useAuthStore';

/**
 * In-app notifications (backlog #157) — GET /api/notifications rendered
 * newest first: one row per order transition that concerned the caller
 * (their listing was paid/cancelled, or their order was
 * completed/refunded/expired). Unread rows carry a Mark read button;
 * read rows show their stamp. Each row links to the order it names.
 * Anonymous visitors bounce to /login like the other buyer pages.
 * In-app only — nothing here claims email or push delivery.
 */
export default function NotificationsPage() {
  const location = useLocation();
  const user = useAuthStore((s) => s.user);
  const notifications = useNotifications(user !== null);
  const unreadCount = useUnreadNotificationCount(user !== null);
  const markRead = useMarkNotificationRead();
  const markAllRead = useMarkAllNotificationsRead();
  const deleteNotification = useDeleteNotification();
  // Saved-search match rows (#182) link back to the search they name:
  // the row carries only its id, so the filter set is resolved from
  // the caller's saved-search list and restored exactly as #164 does.
  const savedSearches = useSavedSearches(user !== null);
  const savedSearchById = new Map(
    (savedSearches.data ?? []).map((search) => [search.id, search]),
  );

  if (!user) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  const rows = notifications.data?.content ?? [];

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-bold">Notifications</h1>
        {(unreadCount.data ?? 0) > 0 && (
          <button
            type="button"
            onClick={() => markAllRead.mutate()}
            disabled={markAllRead.isLoading}
            className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
          >
            Mark all read
          </button>
        )}
      </div>
      {notifications.isLoading && <p>Loading notifications…</p>}
      {notifications.isError && (
        <p role="alert" className="text-red-600">
          Failed to load notifications.
        </p>
      )}
      {notifications.isSuccess && rows.length === 0 && (
        <div className="rounded-lg bg-white p-6 text-center shadow-sm">
          <p className="text-neutral-600">No notifications yet.</p>
        </div>
      )}
      {rows.length > 0 && (
        <ul className="space-y-3">
          {rows.map((notification) => (
            <li
              key={notification.id}
              className="flex items-center justify-between rounded-lg bg-white p-4 shadow-sm"
            >
              <div>
                <p className="font-medium">
                  {describeNotification(
                    notification.type,
                    notification.matchedListingCount,
                    notification.oldPriceCents,
                    notification.newPriceCents,
                  )}
                </p>
                <p className="mt-1 text-sm text-neutral-500">
                  {notification.type === 'LOGIN_NEW_DEVICE' ? (
                    <Link
                      to="/settings/sessions"
                      className="text-blue-600 hover:underline"
                    >
                      Review your sessions and login history
                    </Link>
                  ) : notification.type === 'SAVED_SEARCH_MATCH' ? (
                    notification.savedSearchId !== null &&
                    savedSearchById.has(notification.savedSearchId) ? (
                      <Link
                        to={savedSearchBrowsePath(
                          savedSearchById.get(notification.savedSearchId)!,
                        )}
                        className="text-blue-600 hover:underline"
                      >
                        View matching listings
                      </Link>
                    ) : (
                      // The search was deleted after the match — the
                      // saved-searches list is the honest fallback.
                      <Link to="/favorites" className="text-blue-600 hover:underline">
                        View saved searches
                      </Link>
                    )
                  ) : notification.type === 'REPORT_APPEAL_UPHELD' ||
                    notification.type === 'REPORT_APPEAL_REJECTED' ? (
                    // Backlog #190: the row names the appeal, not an
                    // order — the seller's appeal state lives on the
                    // My listings surface.
                    <Link to="/my-listings" className="text-blue-600 hover:underline">
                      View your listings
                    </Link>
                  ) : notification.type === 'FAVORITE_PRICE_DROP' ? (
                    // Backlog #189: the row names the listing, not an
                    // order — link to the listing at its new price.
                    notification.itemId !== null ? (
                      <Link
                        to={`/items/${notification.itemId}`}
                        className="text-blue-600 hover:underline"
                      >
                        View listing
                      </Link>
                    ) : null
                  ) : notification.orderId !== null ? (
                    <Link
                      to={`/orders/${notification.orderId}`}
                      className="text-blue-600 hover:underline"
                    >
                      Order #{notification.orderId}
                    </Link>
                  ) : null}{' '}
                  · {new Date(notification.createdAt).toLocaleString()}
                </p>
              </div>
              <div className="flex items-center gap-2">
                {notification.readAt === null ? (
                  <button
                    type="button"
                    onClick={() => markRead.mutate(notification.id)}
                    disabled={markRead.isLoading}
                    className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
                  >
                    Mark read
                  </button>
                ) : (
                  <span className="text-sm text-neutral-500">Read</span>
                )}
                <button
                  type="button"
                  aria-label={`Delete notification ${notification.id}`}
                  onClick={() => deleteNotification.mutate(notification.id)}
                  disabled={deleteNotification.isLoading}
                  className="rounded bg-neutral-100 px-3 py-1.5 text-sm text-neutral-700 hover:bg-neutral-200 disabled:opacity-50"
                >
                  Delete
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
