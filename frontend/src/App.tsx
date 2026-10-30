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
        <Link to="/login" className="text-[15px] text-apple-blue transition hover:underline dark:text-apple-sky">
          Login
        </Link>
        <Link to="/register" className="rounded-full bg-apple-blue px-4 py-1.5 text-[15px] font-semibold text-white transition hover:bg-apple-focus">
          Register
        </Link>
      </div>
    );
  }

  return (
    <div className="flex items-center gap-3">
      <Link to="/orders" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
        My orders
      </Link>
      <Link to="/cart" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
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
      <Link to="/offers" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
        Offers
      </Link>
      <Link to="/favorites" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
        Favorites
      </Link>
      <Link to="/messages" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
        Messages
      </Link>
      <Link to="/notifications" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
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
      <Link to="/my-listings" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
        My listings
      </Link>
      <Link to="/seller/orders" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
        Sales
      </Link>
      <Link to="/seller/payouts" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
        Payouts
      </Link>
      <Link to="/items/new" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
        Sell an item
      </Link>
      <Link to="/settings" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
        Settings
      </Link>
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/users" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
          Admin users
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/audit-log" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
          Admin audit log
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/payments" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
          Admin payments
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/reports" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
          Admin reports
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/categories" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
          Admin categories
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/disputes" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
          Admin disputes
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/appeals" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
          Admin appeals
        </Link>
      )}
      {user.roles.includes('ADMIN') && (
        <Link to="/admin/dashboard" className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white">
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
        className="text-sm text-neutral-600 transition hover:text-apple-ink dark:text-neutral-400 dark:hover:text-white"
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
    <div className="flex min-h-screen flex-col bg-apple-parchment font-sans text-apple-ink antialiased dark:bg-black dark:text-neutral-100">
      <header className="sticky top-0 z-20 border-b border-black/[0.08] bg-white/80 backdrop-blur-xl backdrop-saturate-150 dark:border-white/10 dark:bg-neutral-900/80">
        <nav className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3.5">
          <Link
            to="/"
            className="inline-flex items-center text-xl font-semibold tracking-tight text-apple-ink dark:text-white"
          >
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
      <footer className="relative border-t border-black/[0.08] py-6 dark:border-white/10">
        <p className="mx-auto max-w-6xl px-4 text-center text-xs text-apple-muted">
          Campus Marketplace — a portfolio project. Demo only: no real orders or payments.
        </p>
      </footer>
    </div>
  );
}
