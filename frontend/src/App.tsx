import { Suspense } from 'react';
import { Link, Outlet, useNavigate } from 'react-router-dom';
import ErrorBoundary from './components/ErrorBoundary';
import RouteFocusManager from './components/RouteFocusManager';
import { useUnreadMessageCount } from './api/messages';
import { useUnreadNotificationCount } from './api/notifications';
import { useAuthStore } from './store/useAuthStore';

function AuthNav() {
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const navigate = useNavigate();
  // Unread-message badge (#106): the count query only runs signed in;
  // the badge itself renders only above zero.
  const { data: unreadCount } = useUnreadMessageCount(user !== null);
  // Unread-notification bell (#157): same badge pattern as messages;
  // the count query only runs signed in.
  const { data: unreadNotifications } = useUnreadNotificationCount(user !== null);

  if (!user) {
    return (
      <div className="flex items-center gap-3">
        <Link to="/login" className="text-sm text-blue-600 hover:underline">
          Login
        </Link>
        <Link to="/register" className="text-sm text-blue-600 hover:underline">
          Register
        </Link>
      </div>
    );
  }

  return (
    <div className="flex items-center gap-3">
      <Link to="/orders" className="text-sm text-blue-600 hover:underline">
        My orders
      </Link>
      <Link to="/offers" className="text-sm text-blue-600 hover:underline">
        Offers
      </Link>
      <Link to="/favorites" className="text-sm text-blue-600 hover:underline">
        Favorites
      </Link>
      <Link to="/notifications" className="text-sm text-blue-600 hover:underline">
        Notifications
        {unreadNotifications !== undefined && unreadNotifications > 0 && (
          <span
            aria-label={`${unreadNotifications} unread notifications`}
            className="ml-1 rounded-full bg-red-600 px-2 py-0.5 text-xs font-semibold text-white"
          >
            {unreadNotifications}
          </span>
        )}
      </Link>
      <Link to="/my-listings" className="text-sm text-blue-600 hover:underline">
        My listings
      </Link>
      <Link to="/seller/orders" className="text-sm text-blue-600 hover:underline">
        Sales
      </Link>
      <Link to="/items/new" className="text-sm text-blue-600 hover:underline">
        Sell an item
      </Link>
      <Link to="/settings" className="text-sm text-blue-600 hover:underline">
        Settings
      </Link>
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/users" className="text-sm text-blue-600 hover:underline">
          Admin users
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/audit-log" className="text-sm text-blue-600 hover:underline">
          Admin audit log
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/payments" className="text-sm text-blue-600 hover:underline">
          Admin payments
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/reports" className="text-sm text-blue-600 hover:underline">
          Admin reports
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/disputes" className="text-sm text-blue-600 hover:underline">
          Admin disputes
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/appeals" className="text-sm text-blue-600 hover:underline">
          Admin appeals
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/dashboard" className="text-sm text-blue-600 hover:underline">
          Admin dashboard
        </Link>
      )}
      {unreadCount !== undefined && unreadCount > 0 && (
        <span
          aria-label={`${unreadCount} unread messages`}
          className="rounded-full bg-red-600 px-2 py-0.5 text-xs font-semibold text-white"
        >
          {unreadCount} unread
        </span>
      )}
      <span className="text-sm text-neutral-600">{user.username}</span>
      <button
        type="button"
        onClick={() => {
          // logout() always resets local state (even when the server call
          // fails); navigate home either way.
          logout().then(
            () => navigate('/'),
            () => navigate('/'),
          );
        }}
        className="text-sm text-blue-600 hover:underline"
      >
        Logout
      </button>
    </div>
  );
}

export default function App() {
  return (
    <div className="min-h-screen bg-neutral-100 text-neutral-900">
      <header className="bg-white shadow-sm">
        <nav className="mx-auto flex max-w-5xl items-center justify-between px-4 py-3">
          <Link to="/" className="text-lg font-bold">
            Campus Marketplace
          </Link>
          <AuthNav />
        </nav>
      </header>
      <main className="mx-auto max-w-5xl px-4 py-6">
        {/* Route changes move focus to the new page's heading (#167). */}
        <RouteFocusManager />
        {/* A crashed route renders the boundary's fallback inside the shell
            instead of unmounting the whole tree into a blank page. Routes
            are lazy (see main.tsx), so the Suspense fallback covers the
            brief window while a page chunk loads. */}
        <ErrorBoundary>
          <Suspense
            fallback={
              <p role="status" className="text-sm text-neutral-500">
                Loading…
              </p>
            }
          >
            <Outlet />
          </Suspense>
        </ErrorBoundary>
      </main>
    </div>
  );
}
