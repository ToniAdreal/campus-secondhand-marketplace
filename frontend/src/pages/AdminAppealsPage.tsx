import { useEffect, useState } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router-dom';
import {
  APPEAL_STATUSES,
  describeAdminAppealsError,
  useAdminAppeals,
  useRejectAppeal,
  useUpholdAppeal,
  type ReportAppeal,
} from '../api/appeals';
import { isForbidden } from '../api/admin';
import { useAuthStore } from '../store/useAuthStore';

function allowlisted(raw: string | null, values: readonly string[]): string {
  return raw !== null && values.includes(raw) ? raw : '';
}

/**
 * Admin report-appeal queue — /admin/appeals (backlog #190), the
 * frontend for GET /api/admin/appeals and its two resolution
 * transitions, mirroring the /admin/disputes pattern (#169).
 *
 * Authorization is honest about its layers: the nav entry and route
 * render only for ADMIN sessions (client-side only — a non-admin
 * session bounces home and fires no request), but the backend's
 * @PreAuthorize is the real gate. A 403 from the API renders an honest
 * not-authorized state, never a fake/empty queue.
 *
 * The status filter is synced to the URL query string so a filtered
 * view is refresh-safe and shareable; it is an allowlisted select fed
 * by the backend enum values. Uphold/Reject buttons render only on
 * OPEN rows — both resolutions are terminal server-side, a repeat of
 * the same resolution is idempotent, and the opposite resolution on a
 * resolved appeal is a 422 the error mapper turns into plain copy.
 * Upholding restores the listing to AVAILABLE only when it is still
 * WITHDRAWN — each resolved row shows which case applied.
 */
export default function AdminAppealsPage() {
  const user = useAuthStore((s) => s.user);
  const isAdmin = user !== null && user.roles.includes('ADMIN');
  const [searchParams, setSearchParams] = useSearchParams();
  const [page, setPage] = useState(0);
  const [actionError, setActionError] = useState<string | null>(null);

  const status = allowlisted(searchParams.get('status'), APPEAL_STATUSES);

  // A new filter starts again from the first page.
  useEffect(() => {
    setPage(0);
  }, [status]);

  const appeals = useAdminAppeals(page, status, isAdmin);
  const uphold = useUpholdAppeal();
  const reject = useRejectAppeal();

  if (!isAdmin) {
    return <Navigate to="/" replace />;
  }

  if (appeals.isError && isForbidden(appeals.error)) {
    return (
      <div>
        <h1 className="mb-4 text-2xl font-bold">Admin appeals</h1>
        <p role="alert" className="rounded-lg bg-white p-6 text-center text-neutral-600 shadow-sm">
          You are not authorized to view this page. The appeal queue is limited to ADMIN
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

  const handleUphold = (id: number) => {
    setActionError(null);
    uphold.mutate(id, {
      onError: (error) => setActionError(describeAdminAppealsError(error)),
    });
  };

  const handleReject = (id: number) => {
    setActionError(null);
    reject.mutate(id, {
      onError: (error) => setActionError(describeAdminAppealsError(error)),
    });
  };

  const rows: ReportAppeal[] = appeals.data?.content ?? [];
  const totalPages = appeals.data?.totalPages ?? 0;

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">Admin appeals</h1>
      <p className="mb-4 text-sm text-neutral-600">
        A seller whose listing was withdrawn by an actioned report can appeal once. Uphold the
        appeal to restore the listing — it returns to AVAILABLE only when it is still
        withdrawn; a listing that has moved on is left untouched. Reject it and the
        withdrawal stands.
      </p>
      <div className="mb-4">
        <label htmlFor="appeal-status" className="sr-only">
          Filter by status
        </label>
        <select
          id="appeal-status"
          aria-label="Filter by status"
          value={status}
          onChange={(e) => setStatusParam(e.target.value)}
          className="w-full rounded-lg border border-neutral-300 bg-white px-4 py-2 shadow-sm focus:border-sky-500 focus:outline-none sm:w-64"
        >
          <option value="">All statuses</option>
          {APPEAL_STATUSES.map((value) => (
            <option key={value} value={value}>
              {value}
            </option>
          ))}
        </select>
      </div>
      {appeals.isLoading && <p>Loading appeals…</p>}
      {appeals.isError && (
        <p role="alert" className="text-red-600">
          {describeAdminAppealsError(appeals.error)}
        </p>
      )}
      {actionError && (
        <p role="alert" className="mb-3 text-red-600">
          {actionError}
        </p>
      )}
      {appeals.isSuccess && rows.length === 0 && (
        <div className="rounded-lg bg-white p-6 text-center shadow-sm">
          <p className="text-neutral-600">
            {status !== '' ? 'No appeals match this filter.' : 'No appeals yet.'}
          </p>
        </div>
      )}
      {rows.length > 0 && (
        <>
          <div className="overflow-x-auto rounded-lg bg-white shadow-sm">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-neutral-200 text-neutral-500">
                  <th scope="col" className="px-4 py-3 font-medium">Listing</th>
                  <th scope="col" className="px-4 py-3 font-medium">Report</th>
                  <th scope="col" className="px-4 py-3 font-medium">Seller</th>
                  <th scope="col" className="px-4 py-3 font-medium">Reason</th>
                  <th scope="col" className="px-4 py-3 font-medium">Status</th>
                  <th scope="col" className="px-4 py-3 font-medium">Listing outcome</th>
                  <th scope="col" className="px-4 py-3 font-medium">Time</th>
                  <th scope="col" className="px-4 py-3 font-medium">Actions</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="border-b border-neutral-100 last:border-0">
                    <td className="px-4 py-3">
                      <Link to={`/items/${row.itemId}`} className="text-blue-600 hover:underline">
                        Listing #{row.itemId}
                      </Link>
                    </td>
                    <td className="px-4 py-3 font-medium">Report #{row.reportId}</td>
                    <td className="px-4 py-3 font-medium">User #{row.sellerId}</td>
                    <td className="px-4 py-3">{row.reason}</td>
                    <td className="px-4 py-3">{row.status}</td>
                    <td className="px-4 py-3">
                      {row.listingRestored === null
                        ? '—'
                        : row.listingRestored
                          ? 'Restored to AVAILABLE'
                          : 'Left as it was'}
                    </td>
                    <td className="px-4 py-3">{new Date(row.createdAt).toLocaleString()}</td>
                    <td className="px-4 py-3">
                      {row.status === 'OPEN' && (
                        <span className="flex gap-2">
                          <button
                            type="button"
                            onClick={() => handleUphold(row.id)}
                            disabled={uphold.isLoading || reject.isLoading}
                            className="rounded bg-emerald-600 px-3 py-1 text-xs font-medium text-white hover:bg-emerald-700 disabled:opacity-50"
                          >
                            Uphold appeal
                          </button>
                          <button
                            type="button"
                            onClick={() => handleReject(row.id)}
                            disabled={uphold.isLoading || reject.isLoading}
                            className="rounded bg-neutral-200 px-3 py-1 text-xs font-medium text-neutral-800 hover:bg-neutral-300 disabled:opacity-50"
                          >
                            Reject appeal
                          </button>
                        </span>
                      )}
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
              disabled={page <= 0 || appeals.isFetching}
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
              disabled={page + 1 >= totalPages || appeals.isFetching}
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
