import { Link, Navigate, useLocation } from 'react-router-dom';
import {
  describeNotification,
  useMarkNotificationRead,
  useNotifications,
} from '../api/notifications';
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
  const markRead = useMarkNotificationRead();

  if (!user) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  const rows = notifications.data?.content ?? [];

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">Notifications</h1>
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
                <p className="font-medium">{describeNotification(notification.type)}</p>
                <p className="mt-1 text-sm text-neutral-500">
                  <Link
                    to={`/orders/${notification.orderId}`}
                    className="text-blue-600 hover:underline"
                  >
                    Order #{notification.orderId}
                  </Link>{' '}
                  · {new Date(notification.createdAt).toLocaleString()}
                </p>
              </div>
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
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
