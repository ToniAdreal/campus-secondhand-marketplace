import axios from 'axios';
import { Link, Navigate, useLocation } from 'react-router-dom';
import { describeOrderError, useOrdersInfinite } from '../api/orders';
import OrderPspReferences from '../components/OrderPspReferences';
import OrderStatusChip from '../components/OrderStatusChip';
import { useAuthStore } from '../store/useAuthStore';

/**
 * My orders — the buyer's own order history (GET /api/orders, newest first)
 * as a "load more" infinite list. Each row carries a status chip and links
 * to the order confirmation page for that order's lifecycle actions (pay,
 * cancel); the page itself is read-only. Rows that carry mock PSP
 * references (paid / refunded orders, backlog #119) render them under the
 * amount line via the shared OrderPspReferences component, labelled as
 * demo references.
 *
 * Auth: anonymous visitors bounce to /login like the other buyer pages. A
 * 401 mid-session means the credential and the httpOnly cookie both died,
 * so the store's user is gone too — bounce to /login there as well.
 */

function isUnauthorized(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 401;
}

export default function MyOrdersPage() {
  const location = useLocation();
  const user = useAuthStore((s) => s.user);
  // Anonymous visitors never fire the list request — they bounce to /login
  // on the next render; the mid-session 401 path still handles the
  // credential-died-after-login case.
  const orders = useOrdersInfinite(20, user !== null);

  if (!user) {
    // Buying requires a session; LoginPage bounces back here afterwards.
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  if (orders.isError && isUnauthorized(orders.error)) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  const stitched = orders.data?.pages.flatMap((page) => page.content) ?? [];

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">My orders</h1>
      {orders.isLoading && <p>Loading orders…</p>}
      {orders.isError && (
        <p role="alert" className="text-red-600">
          {describeOrderError(orders.error)}
        </p>
      )}
      {orders.isSuccess && stitched.length === 0 && (
        <div className="rounded-lg bg-white p-6 text-center shadow-sm">
          <p className="text-neutral-600">You haven&apos;t bought anything yet.</p>
          <Link to="/" className="mt-2 inline-block text-sm text-blue-600 hover:underline">
            Browse listings
          </Link>
        </div>
      )}
      {stitched.length > 0 && (
        <>
          <ul className="space-y-3">
            {stitched.map((order) => (
              <li key={order.id} className="rounded-lg bg-white p-4 shadow-sm">
                <Link
                  to={`/orders/${order.id}`}
                  className="flex items-center justify-between gap-3 hover:underline"
                >
                  <span className="font-semibold">Order #{order.id}</span>
                  <OrderStatusChip status={order.status} />
                </Link>
                <div className="mt-1 flex items-center gap-4 text-sm text-neutral-500">
                  <span>¥{(order.amountCents / 100).toFixed(2)}</span>
                  <span>{new Date(order.createdAt).toLocaleDateString()}</span>
                </div>
                <OrderPspReferences order={order} />
              </li>
            ))}
          </ul>
          {orders.hasNextPage && (
            <button
              type="button"
              onClick={() => void orders.fetchNextPage()}
              disabled={orders.isFetchingNextPage}
              className="mt-4 rounded bg-neutral-800 px-5 py-2 text-sm font-medium text-white hover:bg-neutral-700 disabled:opacity-50"
            >
              {orders.isFetchingNextPage ? 'Loading…' : 'Load more'}
            </button>
          )}
        </>
      )}
    </div>
  );
}
