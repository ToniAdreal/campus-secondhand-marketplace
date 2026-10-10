import { useEffect, useState } from 'react';
import { Navigate, useSearchParams } from 'react-router-dom';
import {
  AUDIT_ACTIONS,
  AUDIT_TARGET_TYPES,
  describeAuditLogError,
  isForbidden,
  useAdminAuditLog,
  type AuditLogEntry,
} from '../api/admin';
import { useDebouncedSearchParam } from '../hooks/useDebouncedSearchParam';
import { useAuthStore } from '../store/useAuthStore';

/** Positive-integer URL param, or null (no filter) for anything else. */
export function parsePositiveId(raw: string): number | null {
  if (raw.trim() === '') {
    return null;
  }
  const parsed = Number(raw);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

function allowlisted(raw: string | null, values: readonly string[]): string {
  return raw !== null && values.includes(raw) ? raw : '';
}

/**
 * Admin audit log — /admin/audit-log (backlog #120), the frontend for
 * GET /api/admin/audit-log (#109).
 *
 * Authorization is honest about its layers, mirroring /admin/users
 * (#111): the nav entry and route are hidden from non-ADMIN sessions
 * (client-side only — a non-admin session bounces home and fires no
 * request), but the backend's @PreAuthorize is the real gate. A 403 from
 * the API renders an honest not-authorized state, never a fake/empty
 * trail.
 *
 * The four filters (actorUserId / action / targetType / targetId) are
 * synced to the URL query string so a filtered view is refresh-safe and
 * shareable. action/targetType are allowlisted selects fed by the
 * backend enum values, so the SPA never sends a value the backend
 * would 400; the id inputs go through the debounced URL pattern and
 * only positive integers reach the query.
 */
export default function AdminAuditLogPage() {
  const user = useAuthStore((s) => s.user);
  const isAdmin = user !== null && user.roles.includes('ADMIN');
  const [searchParams, setSearchParams] = useSearchParams();
  const [actorInput, actorCommitted, setActorInput] =
    useDebouncedSearchParam('actorUserId', 300);
  const [targetInput, targetCommitted, setTargetInput] =
    useDebouncedSearchParam('targetId', 300);
  const [page, setPage] = useState(0);

  const action = allowlisted(searchParams.get('action'), AUDIT_ACTIONS);
  const targetType = allowlisted(searchParams.get('targetType'), AUDIT_TARGET_TYPES);
  const actorUserId = parsePositiveId(actorCommitted);
  const targetId = parsePositiveId(targetCommitted);

  // A new filter starts again from the first page.
  useEffect(() => {
    setPage(0);
  }, [actorUserId, action, targetType, targetId]);

  const auditLog = useAdminAuditLog(
    page,
    { actorUserId, action, targetType, targetId },
    isAdmin,
  );

  if (!isAdmin) {
    return <Navigate to="/" replace />;
  }

  if (auditLog.isError && isForbidden(auditLog.error)) {
    return (
      <div>
        <h1 className="mb-4 text-2xl font-bold">Admin audit log</h1>
        <p role="alert" className="rounded-lg bg-white p-6 text-center text-neutral-600 shadow-sm">
          You are not authorized to view this page. The audit log is limited to ADMIN
          accounts, and the server refused this request.
        </p>
      </div>
    );
  }

  const setEnumParam = (name: 'action' | 'targetType', value: string) => {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value === '') {
          next.delete(name);
        } else {
          next.set(name, value);
        }
        return next;
      },
      { replace: true },
    );
  };

  const rows: AuditLogEntry[] = auditLog.data?.content ?? [];
  const totalPages = auditLog.data?.totalPages ?? 0;
  const anyFilter =
    actorUserId !== null || action !== '' || targetType !== '' || targetId !== null;

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">Admin audit log</h1>
      <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <div>
          <label htmlFor="audit-actor" className="sr-only">
            Filter by actor user ID
          </label>
          <input
            id="audit-actor"
            type="search"
            inputMode="numeric"
            value={actorInput}
            onChange={(e) => setActorInput(e.target.value)}
            placeholder="Actor user ID"
            className="w-full rounded-lg border border-neutral-300 bg-white px-4 py-2 shadow-sm focus:border-sky-500 focus:outline-none"
          />
        </div>
        <div>
          <label htmlFor="audit-action" className="sr-only">
            Filter by action
          </label>
          <select
            id="audit-action"
            aria-label="Filter by action"
            value={action}
            onChange={(e) => setEnumParam('action', e.target.value)}
            className="w-full rounded-lg border border-neutral-300 bg-white px-4 py-2 shadow-sm focus:border-sky-500 focus:outline-none"
          >
            <option value="">All actions</option>
            {AUDIT_ACTIONS.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="audit-target-type" className="sr-only">
            Filter by target type
          </label>
          <select
            id="audit-target-type"
            aria-label="Filter by target type"
            value={targetType}
            onChange={(e) => setEnumParam('targetType', e.target.value)}
            className="w-full rounded-lg border border-neutral-300 bg-white px-4 py-2 shadow-sm focus:border-sky-500 focus:outline-none"
          >
            <option value="">All target types</option>
            {AUDIT_TARGET_TYPES.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="audit-target-id" className="sr-only">
            Filter by target ID
          </label>
          <input
            id="audit-target-id"
            type="search"
            inputMode="numeric"
            value={targetInput}
            onChange={(e) => setTargetInput(e.target.value)}
            placeholder="Target ID"
            className="w-full rounded-lg border border-neutral-300 bg-white px-4 py-2 shadow-sm focus:border-sky-500 focus:outline-none"
          />
        </div>
      </div>
      {auditLog.isLoading && <p>Loading audit log…</p>}
      {auditLog.isError && (
        <p role="alert" className="text-red-600">
          {describeAuditLogError(auditLog.error)}
        </p>
      )}
      {auditLog.isSuccess && rows.length === 0 && (
        <div className="rounded-lg bg-white p-6 text-center shadow-sm">
          <p className="text-neutral-600">
            {anyFilter ? 'No audit entries match these filters.' : 'No audit entries yet.'}
          </p>
        </div>
      )}
      {rows.length > 0 && (
        <>
          <div className="overflow-x-auto rounded-lg bg-white shadow-sm">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-neutral-200 text-neutral-500">
                  <th scope="col" className="px-4 py-3 font-medium">Actor</th>
                  <th scope="col" className="px-4 py-3 font-medium">Action</th>
                  <th scope="col" className="px-4 py-3 font-medium">Target</th>
                  <th scope="col" className="px-4 py-3 font-medium">Time</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="border-b border-neutral-100 last:border-0">
                    <td className="px-4 py-3 font-medium">User #{row.actorUserId}</td>
                    <td className="px-4 py-3">{row.action}</td>
                    <td className="px-4 py-3">
                      {row.targetType} #{row.targetId}
                    </td>
                    <td className="px-4 py-3">{new Date(row.createdAt).toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="mt-4 flex items-center gap-3">
            <button
              type="button"
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={page <= 0 || auditLog.isFetching}
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
              disabled={page + 1 >= totalPages || auditLog.isFetching}
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
