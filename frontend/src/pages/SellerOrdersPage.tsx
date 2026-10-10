import { useState } from 'react';
import axios from 'axios';
import { Link, Navigate, useLocation } from 'react-router-dom';
import {
  describeCompleteError,
  describeOrderError,
  describeRefundError,
  useCompleteOrder,
  useRefundOrder,
  useSellerOrdersInfinite,
  useSellerSalesSummary,
} from '../api/orders';
import OrderStatusChip from '../components/OrderStatusChip';
import { useAuthStore } from '../store/useAuthStore';

/**
 * Seller orders — the orders buyers placed on the caller's listings
 * (GET /api/seller/orders, newest first) as a "load more" infinite list.
 * A sales-summary header (GET /api/seller/orders/summary, backlog #108)
 * renders the seller's orders / completed / gross totals — all-time and
 * for the current calendar month — from a single summary query.
 * Mirrors MyOrdersPage but reads the seller endpoint and rows link to the
 * listing (the seller cares about which item is moving), not the order
 * confirmation page.
 *
 * Auth: anonymous visitors bounce to /login before any request fires, and a
 * 401 mid-session (credential died after login) bounces too. Note the
 * backend scopes strictly to the caller's sellerId — a logged-in user with
 * no listings gets the empty state, not a 403, so this page is visible to
 * every logged-in user, not just "sellers".
 *
 * Lifecycle actions: each PAID row carries the seller-side transitions —
 * "Complete order" (POST /api/orders/{id}/complete, the seller confirms
 * the handoff; the listing flips to SOLD) and "Refund order"
 * (POST /api/orders/{id}/refund, mock PSP; the listing goes back to
 * AVAILABLE). Both endpoints are seller/ADMIN-only and PAID-only on the
 * backend, so the buttons render only for PAID rows on this seller-scoped
 * page — buyers never see them (their surface is MyOrdersPage, which has
 * no such wiring), and PENDING/terminal rows show none. Both transitions
 * are idempotent server-side (re-complete / re-refund return the order),
 * so a double-click cannot double-apply.
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
  // Sales summary header (backlog #108): one summary query, independent
  // of the list — a summary failure hides the header instead of blocking
  // the orders below, which stay the authoritative view.
  const summaryQuery = useSellerSalesSummary(user !== null);
  const completeOrder = useCompleteOrder();
  const refundOrder = useRefundOrder();
  const [actionError, setActionError] = useState<string | null>(null);

  if (!user) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  if (orders.isError && isUnauthorized(orders.error)) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  const stitched = orders.data?.pages.flatMap((page) => page.content) ?? [];

  const handleComplete = (orderId: number) => {
    setActionError(null);
    completeOrder.mutate(orderId, {
      onError: (error) => setActionError(describeCompleteError(error)),
    });
  };

  const handleRefund = (orderId: number) => {
    setActionError(null);
    // Refunding hands the (mock) money back and re-lists the item — ask
    // first, mirroring the buyer cancel flow's confirm dialog.
    if (
      !window.confirm(
        'Refund this order? The buyer gets the (demo) payment back and the listing becomes available again.',
      )
    ) {
      return;
    }
    refundOrder.mutate(orderId, {
      onError: (error) => setActionError(describeRefundError(error)),
    });
  };

  const summary = summaryQuery.data;
  // Render only a well-formed summary — a malformed payload hides the
  // header rather than printing NaN totals over the list.
  const showSummary =
    summary != null &&
    Number.isFinite(summary.totalOrders) &&
    Number.isFinite(summary.completedCount) &&
    Number.isFinite(summary.grossCents) &&
    summary.currentMonth != null;

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">Orders on my listings</h1>
      {showSummary && (
        <section aria-label="Sales summary" className="mb-4 rounded-lg bg-white p-4 shadow-sm">
          <p className="font-semibold">
            Sales summary: {summary.totalOrders} orders · {summary.completedCount} completed · ¥
            {(summary.grossCents / 100).toFixed(2)} gross
          </p>
          <p className="mt-1 text-sm text-neutral-500">
            This month: {summary.currentMonth.totalOrders} orders ·{' '}
            {summary.currentMonth.completedCount} completed · ¥
            {(summary.currentMonth.grossCents / 100).toFixed(2)} gross
          </p>
        </section>
      )}
      {orders.isLoading && <p>Loading orders…</p>}
      {orders.isError && (
        <p role="alert" className="text-red-600">
          {describeOrderError(orders.error)}
        </p>
      )}
      {actionError && (
        <p role="alert" className="mb-4 text-sm text-red-600">
          {actionError}
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
                {order.status === 'PAID' && (
                  <div className="mt-3 flex gap-2">
                    <button
                      type="button"
                      onClick={() => handleComplete(order.id)}
                      disabled={
                        (completeOrder.isLoading && completeOrder.variables === order.id) ||
                        (refundOrder.isLoading && refundOrder.variables === order.id)
                      }
                      className="rounded bg-green-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-50"
                    >
                      {completeOrder.isLoading && completeOrder.variables === order.id
                        ? 'Completing…'
                        : 'Complete order'}
                    </button>
                    <button
                      type="button"
                      onClick={() => handleRefund(order.id)}
                      disabled={
                        (completeOrder.isLoading && completeOrder.variables === order.id) ||
                        (refundOrder.isLoading && refundOrder.variables === order.id)
                      }
                      className="rounded border border-red-600 px-4 py-1.5 text-sm font-medium text-red-600 hover:bg-red-50 disabled:opacity-50"
                    >
                      {refundOrder.isLoading && refundOrder.variables === order.id
                        ? 'Refunding…'
                        : 'Refund order'}
                    </button>
                  </div>
                )}
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
