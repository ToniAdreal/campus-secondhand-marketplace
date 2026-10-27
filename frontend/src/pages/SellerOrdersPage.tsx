import { useState } from 'react';
import axios from 'axios';
import { Link, Navigate, useLocation, useSearchParams } from 'react-router-dom';
import {
  ORDER_STATUSES,
  describeCompleteError,
  describeOrderError,
  describeRefundError,
  describeShipError,
  useCompleteOrder,
  useRefundOrder,
  useSellerOrdersInfinite,
  useSellerSalesSummary,
  useShipOrder,
} from '../api/orders';
import { OrderDisputeEvidence } from '../components/DisputeEvidencePanel';
import OrderPspReferences from '../components/OrderPspReferences';
import PartialRefundNote from '../components/PartialRefundNote';
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
 * Mock PSP references (backlog #119): rows whose order carries a capture /
 * refund id render them under the amount line via OrderPspReferences,
 * labelled as demo references — after a refund the refetch shows both ids
 * on the REFUNDED row with no extra wiring.
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
 *
 * Handover code (backlog #179): completing also requires the buyer's
 * 6-digit handover code, entered in the per-row field next to the
 * buttons — the buyer hands it over when they receive the item. A
 * missing/wrong code is a 400 mapped to friendly copy, and repeated
 * failures lock the code server-side (the buyer can regenerate one).
 *
 * Shipped path (backlog #222): each PAID row also carries a ship form
 * (carrier + tracking number, POST /api/orders/{id}/ship) as the
 * alternative to the handover completion — the seller chooses one path
 * per order, and the server rejects the other path's transition with
 * 422 afterwards. SHIPPED rows show the recorded carrier + tracking
 * number; the listing stays RESERVED until the buyer confirms receipt.
 *
 * Status filter (backlog #237): a dropdown above the list, URL-synced
 * (?status=, like #84's category filter) and combinable with the
 * infinite scroll — the backend already accepted ?status= (#123), but
 * the page rendered no filter, so a seller with a long ledger could not
 * narrow to PAID or SHIPPED without paging. "All statuses" clears it.
 */

function isUnauthorized(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 401;
}

// A hand-typed ?status= that is not an OrderStatus value falls back to
// "All statuses" instead of reaching the backend's enum-bound ?status=
// (#123), which would 400 — the select only ever offers valid values.
function allowlisted(raw: string | null): string {
  return raw !== null && (ORDER_STATUSES as readonly string[]).includes(raw) ? raw : '';
}

export default function SellerOrdersPage() {
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const user = useAuthStore((s) => s.user);
  // Status filter (backlog #237): URL-synced like #84's category filter
  // and the admin disputes filter — refresh-safe, shareable, and it
  // composes with the infinite scroll (the status rides the query key,
  // so a new filter restarts at page 0). "All statuses" deletes ?status=.
  const status = allowlisted(searchParams.get('status'));
  // Anonymous visitors never fire the list request — they bounce to /login
  // on the next render; the mid-session 401 path still handles the
  // credential-died-after-login case.
  const orders = useSellerOrdersInfinite(20, user !== null, status);
  // Sales summary header (backlog #108): one summary query, independent
  // of the list — a summary failure hides the header instead of blocking
  // the orders below, which stay the authoritative view.
  const summaryQuery = useSellerSalesSummary(user !== null);
  const completeOrder = useCompleteOrder();
  const refundOrder = useRefundOrder();
  const shipOrder = useShipOrder();
  const [actionError, setActionError] = useState<string | null>(null);
  // Per-row ship forms (#222), keyed by order id — carrier + tracking
  // number belong to that row's shipment, so the inputs are independent.
  const [shipForms, setShipForms] = useState<
    Record<number, { carrier: string; trackingNumber: string }>
  >({});
  // Per-row handover codes (#179), keyed by order id — each row's code
  // belongs to that row's buyer, so the inputs are independent.
  const [handoverCodes, setHandoverCodes] = useState<Record<number, string>>({});
  // Partial-refund dialog (backlog #210): which row's dialog is open, and
  // its amount field in yuan (prefilled with the remaining refundable
  // amount, so confirming untouched is exactly the old full refund).
  const [refundDialogOrderId, setRefundDialogOrderId] = useState<number | null>(null);
  const [refundAmountYuan, setRefundAmountYuan] = useState<string>('');

  const refundPendingFor = (orderId: number): boolean => {
    const variables = refundOrder.variables;
    return (
      variables === orderId ||
      (typeof variables === 'object' && variables !== null && variables.orderId === orderId)
    );
  };

  if (!user) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  if (orders.isError && isUnauthorized(orders.error)) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  const stitched = orders.data?.pages.flatMap((page) => page.content) ?? [];

  const setStatusParam = (value: string) => {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value === '') {
          next.delete('status');
        } else {
          next.set('status', value);
        }
        return next;
      },
      { replace: true },
    );
  };

  const handleComplete = (orderId: number) => {
    setActionError(null);
    completeOrder.mutate(
      { orderId, handoverCode: handoverCodes[orderId]?.trim() || undefined },
      {
        onError: (error) => setActionError(describeCompleteError(error)),
      },
    );
  };

  const handleShip = (orderId: number) => {
    setActionError(null);
    const form = shipForms[orderId] ?? { carrier: '', trackingNumber: '' };
    const carrier = form.carrier.trim();
    const trackingNumber = form.trackingNumber.trim();
    if (!carrier || !trackingNumber) {
      setActionError('Enter a carrier and tracking number to ship the order.');
      return;
    }
    shipOrder.mutate(
      { orderId, carrier, trackingNumber },
      {
        onError: (error) => setActionError(describeShipError(error)),
        onSuccess: () =>
          setShipForms((prev) => ({ ...prev, [orderId]: { carrier: '', trackingNumber: '' } })),
      },
    );
  };

  const openRefundDialog = (order: (typeof stitched)[number]) => {
    setActionError(null);
    const remainingCents = order.amountCents - (order.refundedAmountCents ?? 0);
    setRefundAmountYuan((remainingCents / 100).toFixed(2));
    setRefundDialogOrderId(order.id);
  };

  const handleRefund = (order: (typeof stitched)[number]) => {
    setActionError(null);
    // Refunding hands the (mock) money back — ask first, in the refund
    // dialog (backlog #210): the amount field defaults to everything
    // still refundable, so the untouched confirmation is the old full
    // refund (empty body); a smaller amount posts {amountCents} and the
    // order stays PAID until the refunded total reaches its amount.
    const remainingCents = order.amountCents - (order.refundedAmountCents ?? 0);
    const cents = Math.round(Number(refundAmountYuan) * 100);
    if (!Number.isFinite(cents) || cents <= 0) {
      setActionError('Enter a refund amount greater than zero.');
      return;
    }
    if (cents > remainingCents) {
      setActionError('That amount is more than what is left to refund on this order.');
      return;
    }
    const onError = (error: unknown) => setActionError(describeRefundError(error));
    const onSuccess = () => setRefundDialogOrderId(null);
    if (cents === remainingCents) {
      refundOrder.mutate(order.id, { onError, onSuccess });
    } else {
      refundOrder.mutate({ orderId: order.id, amountCents: cents }, { onError, onSuccess });
    }
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
      <div className="mb-4">
        <label htmlFor="seller-order-status" className="sr-only">
          Filter by status
        </label>
        <select
          id="seller-order-status"
          aria-label="Filter by status"
          value={status}
          onChange={(e) => setStatusParam(e.target.value)}
          className="w-full rounded-lg border border-neutral-300 bg-white px-4 py-2 shadow-sm focus:border-sky-500 focus:outline-none sm:w-64"
        >
          <option value="">All statuses</option>
          {ORDER_STATUSES.map((value) => (
            <option key={value} value={value}>
              {value}
            </option>
          ))}
        </select>
      </div>
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
          <p className="text-neutral-600">
            {status !== ''
              ? `No ${status} orders on your listings yet.`
              : 'No orders on your listings yet.'}
          </p>
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
                <PartialRefundNote order={order} />
                <OrderPspReferences order={order} />
                {order.status === 'DISPUTED' && <OrderDisputeEvidence orderId={order.id} />}
                {order.status === 'PAID' && (
                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    <label className="flex items-center gap-2 text-sm text-neutral-600">
                      Handover code
                      <input
                        type="text"
                        inputMode="numeric"
                        autoComplete="one-time-code"
                        maxLength={6}
                        placeholder="6-digit code from the buyer"
                        value={handoverCodes[order.id] ?? ''}
                        onChange={(event) =>
                          setHandoverCodes((prev) => ({
                            ...prev,
                            [order.id]: event.target.value,
                          }))
                        }
                        className="w-44 rounded border border-neutral-300 px-2 py-1.5 text-sm"
                      />
                    </label>
                    <button
                      type="button"
                      onClick={() => handleComplete(order.id)}
                      disabled={
                        (completeOrder.isLoading && completeOrder.variables?.orderId === order.id) ||
                        (refundOrder.isLoading && refundPendingFor(order.id))
                      }
                      className="rounded bg-green-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-50"
                    >
                      {completeOrder.isLoading && completeOrder.variables?.orderId === order.id
                        ? 'Completing…'
                        : 'Complete order'}
                    </button>
                    <button
                      type="button"
                      onClick={() => openRefundDialog(order)}
                      disabled={
                        (completeOrder.isLoading && completeOrder.variables?.orderId === order.id) ||
                        (refundOrder.isLoading && refundPendingFor(order.id))
                      }
                      className="rounded border border-red-600 px-4 py-1.5 text-sm font-medium text-red-600 hover:bg-red-50 disabled:opacity-50"
                    >
                      {refundOrder.isLoading && refundPendingFor(order.id)
                        ? 'Refunding…'
                        : 'Refund order'}
                    </button>
                  </div>
                )}
                {order.status === 'PAID' && (
                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    <label className="flex items-center gap-2 text-sm text-neutral-600">
                      Carrier
                      <input
                        type="text"
                        aria-label="Carrier"
                        placeholder="e.g. SF Express"
                        maxLength={64}
                        value={shipForms[order.id]?.carrier ?? ''}
                        onChange={(event) =>
                          setShipForms((prev) => ({
                            ...prev,
                            [order.id]: {
                              carrier: event.target.value,
                              trackingNumber: prev[order.id]?.trackingNumber ?? '',
                            },
                          }))
                        }
                        className="w-36 rounded border border-neutral-300 px-2 py-1.5 text-sm"
                      />
                    </label>
                    <label className="flex items-center gap-2 text-sm text-neutral-600">
                      Tracking number
                      <input
                        type="text"
                        aria-label="Tracking number"
                        placeholder="Tracking number"
                        maxLength={128}
                        value={shipForms[order.id]?.trackingNumber ?? ''}
                        onChange={(event) =>
                          setShipForms((prev) => ({
                            ...prev,
                            [order.id]: {
                              carrier: prev[order.id]?.carrier ?? '',
                              trackingNumber: event.target.value,
                            },
                          }))
                        }
                        className="w-44 rounded border border-neutral-300 px-2 py-1.5 text-sm"
                      />
                    </label>
                    <button
                      type="button"
                      onClick={() => handleShip(order.id)}
                      disabled={shipOrder.isLoading && shipOrder.variables?.orderId === order.id}
                      className="rounded bg-teal-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-teal-700 disabled:opacity-50"
                    >
                      {shipOrder.isLoading && shipOrder.variables?.orderId === order.id
                        ? 'Shipping…'
                        : 'Ship order'}
                    </button>
                    <span className="text-sm text-neutral-500">
                      Or complete the in-person handoff above — shipping and handover are
                      alternative paths.
                    </span>
                  </div>
                )}
                {order.status === 'SHIPPED' && (
                  <p className="mt-2 text-sm text-neutral-600">
                    Shipped via {order.carrier ?? 'unknown carrier'} — tracking{' '}
                    {order.trackingNumber ?? 'not provided'}. The listing stays reserved until
                    the buyer confirms receipt.
                  </p>
                )}
                {order.status === 'PAID' && refundDialogOrderId === order.id && (
                  <div className="mt-3 flex flex-wrap items-center gap-2 rounded border border-red-200 bg-red-50 p-3">
                    <label className="flex items-center gap-2 text-sm text-neutral-700">
                      Refund amount (¥)
                      <input
                        type="text"
                        inputMode="decimal"
                        aria-label="Refund amount (¥)"
                        value={refundAmountYuan}
                        onChange={(event) => setRefundAmountYuan(event.target.value)}
                        className="w-28 rounded border border-neutral-300 px-2 py-1.5 text-sm"
                      />
                    </label>
                    <span className="text-sm text-neutral-500">
                      of ¥{((order.amountCents - (order.refundedAmountCents ?? 0)) / 100).toFixed(2)}{' '}
                      still refundable — a partial refund keeps the order paid; refunding the rest
                      re-lists the item.
                    </span>
                    <button
                      type="button"
                      onClick={() => handleRefund(order)}
                      disabled={refundOrder.isLoading && refundPendingFor(order.id)}
                      className="rounded bg-red-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
                    >
                      Confirm refund
                    </button>
                    <button
                      type="button"
                      onClick={() => setRefundDialogOrderId(null)}
                      className="rounded border border-neutral-300 px-4 py-1.5 text-sm font-medium text-neutral-600 hover:bg-white"
                    >
                      Cancel
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
