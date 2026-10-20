import { useEffect, useState } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router-dom';
import {
  DISPUTE_STATUSES,
  describeAdminDisputesError,
  useAdminDisputes,
  useResolveDisputeRefund,
  useResolveDisputeRelease,
  type OrderDispute,
} from '../api/disputes';
import { isForbidden } from '../api/admin';
import DisputeEvidencePanel from '../components/DisputeEvidencePanel';
import { useAuthStore } from '../store/useAuthStore';

function allowlisted(raw: string | null, values: readonly string[]): string {
  return raw !== null && values.includes(raw) ? raw : '';
}

/**
 * Admin dispute queue — /admin/disputes (backlog #169), the frontend
 * for GET /api/admin/disputes and its two resolution transitions,
 * mirroring the /admin/reports pattern (#160).
 *
 * Authorization is honest about its layers: the nav entry and route
 * render only for ADMIN sessions (client-side only — a non-admin
 * session bounces home and fires no request), but the backend's
 * @PreAuthorize is the real gate. A 403 from the API renders an honest
 * not-authorized state, never a fake/empty queue.
 *
 * The status filter is synced to the URL query string so a filtered
 * view is refresh-safe and shareable; it is an allowlisted select fed
 * by the backend enum values. Resolve buttons render only on OPEN
 * rows — both resolutions are terminal server-side, a repeat of the
 * same resolution is idempotent, and the opposite resolution on a
 * resolved dispute is a 422 the error mapper turns into plain copy.
 * "Resolve with refund" runs the mock PSP refund (no real money moves)
 * and restores the listing to AVAILABLE unless it was already SOLD —
 * each row shows which case applied once resolved.
 */
export default function AdminDisputesPage() {
  const user = useAuthStore((s) => s.user);
  const isAdmin = user !== null && user.roles.includes('ADMIN');
  const [searchParams, setSearchParams] = useSearchParams();
  const [page, setPage] = useState(0);
  const [actionError, setActionError] = useState<string | null>(null);

  const status = allowlisted(searchParams.get('status'), DISPUTE_STATUSES);

  // A new filter starts again from the first page.
  useEffect(() => {
    setPage(0);
  }, [status]);

  const disputes = useAdminDisputes(page, status, isAdmin);
  const resolveRefund = useResolveDisputeRefund();
  const resolveRelease = useResolveDisputeRelease();

  if (!isAdmin) {
    return <Navigate to="/" replace />;
  }

  if (disputes.isError && isForbidden(disputes.error)) {
    return (
      <div>
        <h1 className="mb-4 text-2xl font-bold">Admin disputes</h1>
        <p role="alert" className="rounded-lg bg-white p-6 text-center text-neutral-600 shadow-sm">
          You are not authorized to view this page. The dispute queue is limited to ADMIN
          accounts, and the server refused this request.
        </p>
      </div>
    );
  }

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

  const handleRefund = (id: number) => {
    setActionError(null);
    resolveRefund.mutate(id, {
      onError: (error) => setActionError(describeAdminDisputesError(error)),
    });
  };

  const handleRelease = (id: number) => {
    setActionError(null);
    resolveRelease.mutate(id, {
      onError: (error) => setActionError(describeAdminDisputesError(error)),
    });
  };

  const rows: OrderDispute[] = disputes.data?.content ?? [];
  const totalPages = disputes.data?.totalPages ?? 0;

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">Admin disputes</h1>
      <p className="mb-4 text-sm text-neutral-600">
        A disputed order is frozen — pay, cancel, complete and refund all wait — until you
        resolve it here: refund the buyer (mock payment, no real money moves) or release the
        order back to the status it had before the dispute.
      </p>
      <div className="mb-4">
        <label htmlFor="dispute-status" className="sr-only">
          Filter by status
        </label>
        <select
          id="dispute-status"
          aria-label="Filter by status"
          value={status}
          onChange={(e) => setStatusParam(e.target.value)}
          className="w-full rounded-lg border border-neutral-300 bg-white px-4 py-2 shadow-sm focus:border-sky-500 focus:outline-none sm:w-64"
        >
          <option value="">All statuses</option>
          {DISPUTE_STATUSES.map((value) => (
            <option key={value} value={value}>
              {value}
            </option>
          ))}
        </select>
      </div>
      {disputes.isLoading && <p>Loading disputes…</p>}
      {disputes.isError && (
        <p role="alert" className="text-red-600">
          {describeAdminDisputesError(disputes.error)}
        </p>
      )}
      {actionError && (
        <p role="alert" className="mb-3 text-red-600">
          {actionError}
        </p>
      )}
      {disputes.isSuccess && rows.length === 0 && (
        <div className="rounded-lg bg-white p-6 text-center shadow-sm">
          <p className="text-neutral-600">
            {status !== '' ? 'No disputes match this filter.' : 'No disputes yet.'}
          </p>
        </div>
      )}
      {rows.length > 0 && (
        <>
          <div className="overflow-x-auto rounded-lg bg-white shadow-sm">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-neutral-200 text-neutral-500">
                  <th scope="col" className="px-4 py-3 font-medium">Order</th>
                  <th scope="col" className="px-4 py-3 font-medium">Buyer</th>
                  <th scope="col" className="px-4 py-3 font-medium">Seller</th>
                  <th scope="col" className="px-4 py-3 font-medium">Reason</th>
                  <th scope="col" className="px-4 py-3 font-medium">Was</th>
                  <th scope="col" className="px-4 py-3 font-medium">Status</th>
                  <th scope="col" className="px-4 py-3 font-medium">Listing</th>
                  <th scope="col" className="px-4 py-3 font-medium">Time</th>
                  <th scope="col" className="px-4 py-3 font-medium">Actions</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="border-b border-neutral-100 last:border-0">
                    <td className="px-4 py-3">
                      <Link to={`/orders/${row.orderId}`} className="text-blue-600 hover:underline">
                        Order #{row.orderId}
                      </Link>
                    </td>
                    <td className="px-4 py-3 font-medium">User #{row.buyerId}</td>
                    <td className="px-4 py-3 font-medium">User #{row.sellerId}</td>
                    <td className="px-4 py-3">{row.reason}</td>
                    <td className="px-4 py-3">{row.previousStatus}</td>
                    <td className="px-4 py-3">{row.status}</td>
                    <td className="px-4 py-3">
                      {row.listingRestored === null
                        ? '—'
                        : row.listingRestored
                          ? 'Restored to AVAILABLE'
                          : 'Stayed SOLD'}
                    </td>
                    <td className="px-4 py-3">{new Date(row.createdAt).toLocaleString()}</td>
                    <td className="px-4 py-3">
                      {row.status === 'OPEN' && (
                        <span className="flex gap-2">
                          <button
                            type="button"
                            onClick={() => handleRelease(row.id)}
                            disabled={resolveRefund.isLoading || resolveRelease.isLoading}
                            className="rounded bg-neutral-200 px-3 py-1 text-xs font-medium text-neutral-800 hover:bg-neutral-300 disabled:opacity-50"
                          >
                            Release order
                          </button>
                          <button
                            type="button"
                            onClick={() => handleRefund(row.id)}
                            disabled={resolveRefund.isLoading || resolveRelease.isLoading}
                            className="rounded bg-red-600 px-3 py-1 text-xs font-medium text-white hover:bg-red-700 disabled:opacity-50"
                          >
                            Resolve with refund
                          </button>
                        </span>
                      )}
                      <DisputeEvidencePanel disputeId={row.id} disputeStatus={row.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="mt-4 flex items-center gap-3">
            <button
              type="button"
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={page <= 0 || disputes.isFetching}
              className="rounded bg-neutral-800 px-4 py-1.5 text-sm font-medium text-white hover:bg-neutral-700 disabled:opacity-50"
            >
              Previous
            </button>
            <span className="text-sm text-neutral-600">
              Page {totalPages === 0 ? 0 : page + 1} of {totalPages}
            </span>
            <button
              type="button"
              onClick={() => setPage((p) => p + 1)}
              disabled={page + 1 >= totalPages || disputes.isFetching}
              className="rounded bg-neutral-800 px-4 py-1.5 text-sm font-medium text-white hover:bg-neutral-700 disabled:opacity-50"
            >
              Next
            </button>
          </div>
        </>
      )}
    </div>
  );
}
