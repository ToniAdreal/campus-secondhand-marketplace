import axios from 'axios';
import { useState } from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import { describeDisputeError, useOpenDispute } from '../api/disputes';
import { describeOrderError, useOrdersInfinite, type Order } from '../api/orders';
import { describeReviewError, useCreateReview } from '../api/reviews';
import OrderPspReferences from '../components/OrderPspReferences';
import OrderReceipt from '../components/OrderReceipt';
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

/**
 * Per-order dispute control (backlog #169). PAID and COMPLETED rows can
 * be disputed: the button reveals a reason form (text only — v1 has no
 * evidence upload), and submitting freezes the order in DISPUTED until
 * an ADMIN resolves it. DISPUTED rows show the open state instead.
 *
 * The eligible window (7 days from the PAID/COMPLETED transition) is
 * enforced by the server — the order shape carries no transition
 * timestamp the SPA could check honestly — so the button renders for
 * every PAID/COMPLETED row and a window-expired attempt maps the
 * server's 422 to plain copy via describeDisputeError.
 */
function OrderDisputeControl({ order }: { order: Order }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const openDispute = useOpenDispute();

  if (order.status === 'DISPUTED') {
    return (
      <p className="mt-2 text-sm text-red-700">
        Dispute open — this order is frozen while an admin reviews it.
      </p>
    );
  }
  if (order.status !== 'PAID' && order.status !== 'COMPLETED') {
    return null;
  }
  if (openDispute.isSuccess) {
    return (
      <p className="mt-2 text-sm text-neutral-600">
        Dispute opened — an admin will review it. The order is frozen meanwhile.
      </p>
    );
  }
  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="mt-2 text-sm text-blue-600 hover:underline"
      >
        Report a problem
      </button>
    );
  }
  return (
    <form
      className="mt-2"
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        openDispute.mutate(
          { orderId: order.id, reason: reason.trim() },
          { onError: (err) => setError(describeDisputeError(err)) },
        );
      }}
    >
      <label htmlFor={`dispute-reason-${order.id}`} className="text-sm font-medium">
        What went wrong?
      </label>
      <textarea
        id={`dispute-reason-${order.id}`}
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        maxLength={500}
        rows={3}
        className="mt-1 w-full rounded-lg border border-neutral-300 px-3 py-2 text-sm shadow-sm focus:border-sky-500 focus:outline-none"
      />
      {error && (
        <p role="alert" className="mt-1 text-sm text-red-600">
          {error}
        </p>
      )}
      <div className="mt-2 flex gap-2">
        <button
          type="submit"
          disabled={openDispute.isLoading || reason.trim() === ''}
          className="rounded bg-red-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
        >
          {openDispute.isLoading ? 'Opening…' : 'Open dispute'}
        </button>
        <button
          type="button"
          onClick={() => setOpen(false)}
          className="rounded bg-neutral-200 px-4 py-1.5 text-sm font-medium text-neutral-800 hover:bg-neutral-300"
        >
          Cancel
        </button>
      </div>
    </form>
  );
}

/**
 * Per-order review control (backlog #175). Only COMPLETED rows can be
 * reviewed: the button reveals a rating form (1–5 select + optional
 * text), and submitting posts the trade's one review — reviews are
 * append-only, so there is no edit affordance afterwards. The SPA
 * cannot know whether this order was already reviewed (the order shape
 * carries no review flag), so a repeat attempt maps the server's 409
 * to plain copy via describeReviewError.
 */
function OrderReviewControl({ order }: { order: Order }) {
  const [open, setOpen] = useState(false);
  const [rating, setRating] = useState(5);
  const [body, setBody] = useState('');
  const [error, setError] = useState<string | null>(null);
  const createReview = useCreateReview();

  if (order.status !== 'COMPLETED') {
    return null;
  }
  if (createReview.isSuccess) {
    return (
      <p className="mt-2 text-sm text-neutral-600">
        Review submitted — thanks for helping other buyers.
      </p>
    );
  }
  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="mt-2 ml-4 text-sm text-blue-600 hover:underline"
      >
        Leave a review
      </button>
    );
  }
  return (
    <form
      className="mt-2"
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        createReview.mutate(
          { orderId: order.id, rating, body: body.trim() || undefined },
          { onError: (err) => setError(describeReviewError(err)) },
        );
      }}
    >
      <label htmlFor={`review-rating-${order.id}`} className="text-sm font-medium">
        Your rating
      </label>
      <select
        id={`review-rating-${order.id}`}
        value={rating}
        onChange={(e) => setRating(Number(e.target.value))}
        className="mt-1 ml-2 rounded-lg border border-neutral-300 px-2 py-1 text-sm shadow-sm focus:border-sky-500 focus:outline-none"
      >
        {[5, 4, 3, 2, 1].map((value) => (
          <option key={value} value={value}>
            {value} star{value === 1 ? '' : 's'}
          </option>
        ))}
      </select>
      <label htmlFor={`review-body-${order.id}`} className="mt-2 block text-sm font-medium">
        Anything to add? (optional)
      </label>
      <textarea
        id={`review-body-${order.id}`}
        value={body}
        onChange={(e) => setBody(e.target.value)}
        maxLength={500}
        rows={3}
        className="mt-1 w-full rounded-lg border border-neutral-300 px-3 py-2 text-sm shadow-sm focus:border-sky-500 focus:outline-none"
      />
      {error && (
        <p role="alert" className="mt-1 text-sm text-red-600">
          {error}
        </p>
      )}
      <div className="mt-2 flex gap-2">
        <button
          type="submit"
          disabled={createReview.isLoading}
          className="rounded bg-neutral-800 px-4 py-1.5 text-sm font-medium text-white hover:bg-neutral-700 disabled:opacity-50"
        >
          {createReview.isLoading ? 'Submitting…' : 'Submit review'}
        </button>
        <button
          type="button"
          onClick={() => setOpen(false)}
          className="rounded bg-neutral-200 px-4 py-1.5 text-sm font-medium text-neutral-800 hover:bg-neutral-300"
        >
          Cancel
        </button>
      </div>
    </form>
  );
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
                <OrderReceipt orderId={order.id} />
                <OrderDisputeControl order={order} />
                <OrderReviewControl order={order} />
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
