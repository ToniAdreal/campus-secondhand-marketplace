import { Suspense } from 'react';
import { Link, Outlet, useNavigate } from 'react-router-dom';
import ErrorBoundary from './components/ErrorBoundary';
import RouteFocusManager from './components/RouteFocusManager';
import { useUnreadMessageCount } from './api/messages';
import { useNotificationStream, useUnreadNotificationCount } from './api/notifications';
import { useAuthStore } from './store/useAuthStore';
import { useCartStore } from './store/useCartStore';

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
  // Bundle-cart count (backlog #207): local state only, no request.
  const cartCount = useCartStore((s) => s.items.length);
  // Live bell (#203): SSE events invalidate the notifications subtree;
  // the count query above stays as the fallback when the stream drops.
  useNotificationStream(user !== null);

  if (!user) {
    return (
      <div className="flex items-center gap-3">
        <Link to="/login" className="text-sm font-medium text-neutral-600 transition hover:text-blue-700 dark:text-neutral-300 dark:hover:text-blue-400">
          Login
        </Link>
        <Link to="/register" className="rounded-full bg-blue-600 px-4 py-1.5 text-sm font-semibold text-white shadow-sm transition hover:bg-blue-700">
          Register
        </Link>
      </div>
    );
  }

  return (
    <div className="flex items-center gap-3">
      <Link to="/orders" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
        My orders
      </Link>
      <Link to="/cart" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
        Cart
        {cartCount > 0 && (
          <span
            aria-label={`${cartCount} items in cart`}
            className="ml-1 rounded-full bg-blue-600 px-2 py-0.5 text-xs font-semibold text-white"
          >
            {cartCount}
          </span>
        )}
      </Link>
      <Link to="/offers" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
        Offers
      </Link>
      <Link to="/favorites" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
        Favorites
      </Link>
      <Link to="/notifications" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
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
      <Link to="/my-listings" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
        My listings
      </Link>
      <Link to="/seller/orders" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
        Sales
      </Link>
      <Link to="/seller/payouts" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
        Payouts
      </Link>
      <Link to="/items/new" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
        Sell an item
      </Link>
      <Link to="/settings" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
        Settings
      </Link>
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/users" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
          Admin users
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/audit-log" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
          Admin audit log
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/payments" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
          Admin payments
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/reports" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
          Admin reports
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/categories" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
          Admin categories
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/disputes" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
          Admin disputes
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/appeals" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
          Admin appeals
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/dashboard" className="text-sm text-blue-600 hover:underline dark:text-blue-400">
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
      <span className="text-sm text-neutral-600 dark:text-neutral-300">{user.username}</span>
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
        className="text-sm text-blue-600 hover:underline dark:text-blue-400"
      >
        Logout
      </button>
    </div>
  );
}

export default function App() {
  return (
    // Shell palette (backlog #227): dark: variants carry the dark theme
    // on the light surfaces below — palette only, no layout change.
    <div className="relative flex min-h-screen flex-col bg-gradient-to-b from-blue-50 via-neutral-50 to-neutral-100 antialiased text-neutral-900 dark:from-neutral-900 dark:via-neutral-900 dark:to-neutral-950 dark:text-neutral-100">
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 overflow-hidden">
        <div className="absolute -top-32 right-[12%] h-80 w-80 rounded-full bg-blue-400/20 blur-3xl" />
        <div className="absolute bottom-10 left-[6%] h-72 w-72 rounded-full bg-sky-300/20 blur-3xl" />
      </div>
      <header className="sticky top-0 z-20 border-b border-neutral-200/70 bg-white/90 shadow-sm backdrop-blur dark:border-neutral-700/60 dark:bg-neutral-800/90">
        <nav className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3.5">
          <Link
            to="/"
            className="inline-flex items-center text-xl font-extrabold tracking-tight text-blue-700 dark:text-blue-400"
          >
            <span
              aria-hidden="true"
              className="mr-2.5 inline-flex h-9 w-9 items-center justify-center rounded-xl bg-blue-600 text-white shadow-md shadow-blue-600/30"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-5 w-5">
                <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 10.5V6a3.75 3.75 0 10-7.5 0v4.5m11.356-1.993l1.263 12c.07.665-.45 1.243-1.119 1.243H4.25a1.125 1.125 0 01-1.12-1.243l1.264-12A1.125 1.125 0 015.513 7.5h12.974c.576 0 1.059.435 1.119 1.007z" />
              </svg>
            </span>
            Campus Marketplace
          </Link>
          <AuthNav />
        </nav>
      </header>
      <main className="relative mx-auto w-full max-w-6xl flex-1 px-4 py-8">
        {/* Route changes move focus to the new page's heading (#167). */}
        <RouteFocusManager />
        {/* A crashed route renders the boundary's fallback inside the shell
            instead of unmounting the whole tree into a blank page. Routes
            are lazy (see main.tsx), so the Suspense fallback covers the
            brief window while a page chunk loads. */}
        <ErrorBoundary>
          <Suspense
            fallback={
              <p role="status" className="text-sm text-neutral-500 dark:text-neutral-400">
                Loading…
              </p>
            }
          >
            <Outlet />
          </Suspense>
        </ErrorBoundary>
      </main>
      <footer className="relative border-t border-neutral-200/70 py-6 dark:border-neutral-700/60">
        <p className="mx-auto max-w-6xl px-4 text-center text-xs text-neutral-400 dark:text-neutral-500">
          Campus Marketplace — a portfolio project. Demo only: no real orders or payments.
        </p>
      </footer>
    </div>
  );
}
