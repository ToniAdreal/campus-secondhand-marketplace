import axios from 'axios';
import { Link, Navigate, useLocation } from 'react-router-dom';
import { useSellerPayoutsInfinite, useSellerPayoutTotals } from '../api/payouts';
import { useAuthStore } from '../store/useAuthStore';

/**
 * Seller payouts — the payout ledger for completed orders
 * (GET /api/seller/payouts + /api/seller/payouts/totals, backlog #221).
 * One row per completed order: the settlement figure from completion,
 * any compensating adjustments (a dispute refund after completion shows
 * up as a negative adjustment, never as a rewritten row), and the net.
 *
 * Auth mirrors SellerOrdersPage: anonymous visitors bounce to /login
 * before any request fires, and a 401 mid-session bounces too. A
 * logged-in user with no completed orders gets the empty state, not an
 * error — the backend scopes strictly to the caller's sellerId.
 *
 * Honest scope, stated on the page itself: the mock pays nobody, so a
 * PAID row means the ledger advanced — it is not a bank transfer.
 */
function isUnauthorized(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 401;
}

function formatYuan(cents: number): string {
  return `¥${(cents / 100).toFixed(2)}`;
}

export default function SellerPayoutsPage() {
  const location = useLocation();
  const user = useAuthStore((s) => s.user);
  const payouts = useSellerPayoutsInfinite(20, user !== null);
  const totalsQuery = useSellerPayoutTotals(user !== null);

  if (user === null) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }
  if ((payouts.isError && isUnauthorized(payouts.error)) || (totalsQuery.isError && isUnauthorized(totalsQuery.error))) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  const stitched = payouts.data?.pages.flatMap((page) => page.content) ?? [];
  const totals = totalsQuery.data;
  const showTotals =
    totals != null &&
    Number.isFinite(totals.pendingCents) &&
    Number.isFinite(totals.payableCents) &&
    Number.isFinite(totals.paidCents) &&
    Number.isFinite(totals.adjustmentsCents) &&
    Number.isFinite(totals.netCents);

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">Payouts</h1>
      {showTotals && (
        <section aria-label="Payout totals" className="mb-4 rounded-lg bg-white p-4 shadow-sm">
          <p className="font-semibold">
            Net owed: {formatYuan(totals.netCents)} · {totals.pendingCount} pending (
            {formatYuan(totals.pendingCents)}) · {totals.payableCount} payable (
            {formatYuan(totals.payableCents)}) · {totals.paidCount} paid (
            {formatYuan(totals.paidCents)})
          </p>
          {totals.adjustmentsCents !== 0 && (
            <p className="mt-1 text-sm text-neutral-500">
              Adjustments (refunds after completion): {formatYuan(totals.adjustmentsCents)}
            </p>
          )}
          <p className="mt-1 text-sm text-neutral-500">
            Payouts become payable once the 7-day dispute window passes with no open dispute, and
            are marked paid by the next hourly sweep. The mock pays nobody — a PAID row is a ledger
            state, not a bank transfer.
          </p>
        </section>
      )}
      {payouts.isLoading && <p>Loading payouts…</p>}
      {payouts.isError && (
        <p role="alert" className="text-red-600">
          Something went wrong loading your payouts. Please try again.
        </p>
      )}
      {payouts.isSuccess && stitched.length === 0 && (
        <div className="rounded-lg bg-white p-6 text-center shadow-sm">
          <p className="text-neutral-600">No payouts yet — complete an order to start one.</p>
          <Link to="/seller/orders" className="mt-2 inline-block text-sm text-blue-600 hover:underline">
            View sales
          </Link>
        </div>
      )}
      {stitched.length > 0 && (
        <>
          <ul className="space-y-3">
            {stitched.map((payout) => (
              <li key={payout.id} className="rounded-lg bg-white p-4 shadow-sm">
                <div className="flex items-center justify-between gap-3">
                  <span className="font-semibold">Payout for order #{payout.orderId}</span>
                  <span className="text-sm font-semibold">{payout.status}</span>
                </div>
                <div className="mt-1 flex flex-wrap items-center gap-4 text-sm text-neutral-500">
                  <span>Settlement {formatYuan(payout.amountCents)}</span>
                  {payout.adjustmentsCents !== 0 && (
                    <span>Adjustments {formatYuan(payout.adjustmentsCents)}</span>
                  )}
                  <span className="font-semibold text-neutral-700">Net {formatYuan(payout.netCents)}</span>
                  <span>{new Date(payout.createdAt).toLocaleDateString()}</span>
                </div>
              </li>
            ))}
          </ul>
          {payouts.hasNextPage && (
            <button
              type="button"
              onClick={() => payouts.fetchNextPage()}
              disabled={payouts.isFetchingNextPage}
              className="mt-4 rounded bg-blue-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
            >
              {payouts.isFetchingNextPage ? 'Loading…' : 'Load more'}
            </button>
          )}
        </>
      )}
    </div>
  );
}
