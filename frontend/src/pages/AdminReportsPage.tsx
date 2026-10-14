import { useEffect, useState } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router-dom';
import {
  REPORT_STATUSES,
  describeAdminReportsError,
  useActionReport,
  useAdminReports,
  useDismissReport,
  type ListingReport,
} from '../api/reports';
import { isForbidden } from '../api/admin';
import { useAuthStore } from '../store/useAuthStore';

function allowlisted(raw: string | null, values: readonly string[]): string {
  return raw !== null && values.includes(raw) ? raw : '';
}

/**
 * Admin moderation queue — /admin/reports (backlog #160), the frontend
 * for GET /api/admin/reports and its dismiss/action transitions.
 *
 * Authorization is honest about its layers, mirroring /admin/audit-log
 * (#120): the nav entry and route are hidden from non-ADMIN sessions
 * (client-side only — a non-admin session bounces home and fires no
 * request), but the backend's @PreAuthorize is the real gate. A 403 from
 * the API renders an honest not-authorized state, never a fake/empty
 * queue.
 *
 * The status filter is synced to the URL query string so a filtered
 * view is refresh-safe and shareable; it is an allowlisted select fed by
 * the backend enum values, so the SPA never sends a value the backend
 * would 400. Dismiss / Action render only on OPEN rows — both are
 * terminal server-side, a repeat of the same transition is idempotent,
 * and the opposite transition on a resolved report is a 422 the error
 * mapper turns into plain copy. "Action" withdraws the listing
 * (AVAILABLE → WITHDRAWN) on the backend in the same transaction.
 *
 * Honest scope note the page states outright: reports never hide a
 * listing by themselves — there is no auto-hide on N reports in v1; a
 * human decides here.
 */
export default function AdminReportsPage() {
  const user = useAuthStore((s) => s.user);
  const isAdmin = user !== null && user.roles.includes('ADMIN');
  const [searchParams, setSearchParams] = useSearchParams();
  const [page, setPage] = useState(0);
  const [actionError, setActionError] = useState<string | null>(null);

  const status = allowlisted(searchParams.get('status'), REPORT_STATUSES);

  // A new filter starts again from the first page.
  useEffect(() => {
    setPage(0);
  }, [status]);

  const reports = useAdminReports(page, status, isAdmin);
  const dismissReport = useDismissReport();
  const actionReport = useActionReport();

  if (!isAdmin) {
    return <Navigate to="/" replace />;
  }

  if (reports.isError && isForbidden(reports.error)) {
    return (
      <div>
        <h1 className="mb-4 text-2xl font-bold">Admin reports</h1>
        <p role="alert" className="rounded-lg bg-white p-6 text-center text-neutral-600 shadow-sm">
          You are not authorized to view this page. The moderation queue is limited to ADMIN
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

  const handleDismiss = (id: number) => {
    setActionError(null);
    dismissReport.mutate(id, {
      onError: (error) => setActionError(describeAdminReportsError(error)),
    });
  };

  const handleAction = (id: number) => {
    setActionError(null);
    actionReport.mutate(id, {
      onError: (error) => setActionError(describeAdminReportsError(error)),
    });
  };

  const rows: ListingReport[] = reports.data?.content ?? [];
  const totalPages = reports.data?.totalPages ?? 0;

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">Admin reports</h1>
      <p className="mb-4 text-sm text-neutral-600">
        Reports never hide a listing by themselves — there is no auto-hide. A listing stays
        visible until you dismiss a report or act on it here (acting withdraws the listing).
      </p>
      <div className="mb-4">
        <label htmlFor="report-status" className="sr-only">
          Filter by status
        </label>
        <select
          id="report-status"
          aria-label="Filter by status"
          value={status}
          onChange={(e) => setStatusParam(e.target.value)}
          className="w-full rounded-lg border border-neutral-300 bg-white px-4 py-2 shadow-sm focus:border-sky-500 focus:outline-none sm:w-64"
        >
          <option value="">All statuses</option>
          {REPORT_STATUSES.map((value) => (
            <option key={value} value={value}>
              {value}
            </option>
          ))}
        </select>
      </div>
      {reports.isLoading && <p>Loading reports…</p>}
      {reports.isError && (
        <p role="alert" className="text-red-600">
          {describeAdminReportsError(reports.error)}
        </p>
      )}
      {actionError && (
        <p role="alert" className="mb-3 text-red-600">
          {actionError}
        </p>
      )}
      {reports.isSuccess && rows.length === 0 && (
        <div className="rounded-lg bg-white p-6 text-center shadow-sm">
          <p className="text-neutral-600">
            {status !== '' ? 'No reports match this filter.' : 'No reports yet.'}
          </p>
        </div>
      )}
      {rows.length > 0 && (
        <>
          <div className="overflow-x-auto rounded-lg bg-white shadow-sm">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-neutral-200 text-neutral-500">
                  <th scope="col" className="px-4 py-3 font-medium">Reporter</th>
                  <th scope="col" className="px-4 py-3 font-medium">Listing</th>
                  <th scope="col" className="px-4 py-3 font-medium">Reason</th>
                  <th scope="col" className="px-4 py-3 font-medium">Note</th>
                  <th scope="col" className="px-4 py-3 font-medium">Status</th>
                  <th scope="col" className="px-4 py-3 font-medium">Time</th>
                  <th scope="col" className="px-4 py-3 font-medium">Actions</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="border-b border-neutral-100 last:border-0">
                    <td className="px-4 py-3 font-medium">User #{row.reporterId}</td>
                    <td className="px-4 py-3">
                      <Link to={`/items/${row.itemId}`} className="text-blue-600 hover:underline">
                        Listing #{row.itemId}
                      </Link>
                    </td>
                    <td className="px-4 py-3">{row.reason}</td>
                    <td className="px-4 py-3">{row.note ?? '—'}</td>
                    <td className="px-4 py-3">{row.status}</td>
                    <td className="px-4 py-3">{new Date(row.createdAt).toLocaleString()}</td>
                    <td className="px-4 py-3">
                      {row.status === 'OPEN' && (
                        <span className="flex gap-2">
                          <button
                            type="button"
                            onClick={() => handleDismiss(row.id)}
                            disabled={dismissReport.isLoading || actionReport.isLoading}
                            className="rounded bg-neutral-200 px-3 py-1 text-xs font-medium text-neutral-800 hover:bg-neutral-300 disabled:opacity-50"
                          >
                            Dismiss
                          </button>
                          <button
                            type="button"
                            onClick={() => handleAction(row.id)}
                            disabled={dismissReport.isLoading || actionReport.isLoading}
                            className="rounded bg-red-600 px-3 py-1 text-xs font-medium text-white hover:bg-red-700 disabled:opacity-50"
                          >
                            Action (withdraw listing)
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
              disabled={page <= 0 || reports.isFetching}
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
              disabled={page + 1 >= totalPages || reports.isFetching}
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
