import axios from 'axios';
import { Link, Navigate, useLocation } from 'react-router-dom';
import { describeOrderError, useSellerOrdersInfinite } from '../api/orders';
import OrderStatusChip from '../components/OrderStatusChip';
import { useAuthStore } from '../store/useAuthStore';

/**
 * Seller orders — the orders buyers placed on the caller's listings
 * (GET /api/seller/orders, newest first) as a "load more" infinite list.
 * Mirrors MyOrdersPage but reads the seller endpoint and rows link to the
 * listing (the seller cares about which item is moving), not the order
 * confirmation page.
 *
 * Auth: anonymous visitors bounce to /login before any request fires, and a
 * 401 mid-session (credential died after login) bounces too. Note the
 * backend scopes strictly to the caller's sellerId — a logged-in user with
 * no listings gets the empty state, not a 403, so this page is visible to
 * every logged-in user, not just "sellers".
 */

function isUnauthorized(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 401;
}

export default function SellerOrdersPage() {
  const location = useLocation();
  const user = useAuthStore((s) => s.user);
  // Anonymous visitors never fire the list request — they bounce to /login
  // on the next render; the mid-session 401 path still handles the
  // credential-died-after-login case.
  const orders = useSellerOrdersInfinite(20, user !== null);

  if (!user) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  if (orders.isError && isUnauthorized(orders.error)) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  const stitched = orders.data?.pages.flatMap((page) => page.content) ?? [];

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">Orders on my listings</h1>
      {orders.isLoading && <p>Loading orders…</p>}
      {orders.isError && (
        <p role="alert" className="text-red-600">
          {describeOrderError(orders.error)}
        </p>
      )}
      {orders.isSuccess && stitched.length === 0 && (
        <div className="rounded-lg bg-white p-6 text-center shadow-sm">
          <p className="text-neutral-600">No orders on your listings yet.</p>
          <Link to="/items/new" className="mt-2 inline-block text-sm text-blue-600 hover:underline">
            Sell an item
          </Link>
        </div>
      )}
      {stitched.length > 0 && (
        <>
          <ul className="space-y-3">
            {stitched.map((order) => (
              <li key={order.id} className="rounded-lg bg-white p-4 shadow-sm">
                <Link
                  to={`/items/${order.itemId}`}
                  className="flex items-center justify-between gap-3 hover:underline"
                >
                  <span className="font-semibold">Order #{order.id}</span>
                  <OrderStatusChip status={order.status} />
                </Link>
                <div className="mt-1 flex items-center gap-4 text-sm text-neutral-500">
                  <span>Buyer #{order.buyerId}</span>
                  <span>¥{(order.amountCents / 100).toFixed(2)}</span>
                  <span>{new Date(order.createdAt).toLocaleDateString()}</span>
                </div>
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
