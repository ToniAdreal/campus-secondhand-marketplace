import { useState } from 'react';
import { Navigate } from 'react-router-dom';
import {
  WEBHOOK_OUTCOMES,
  describeSettlementError,
  describeWebhookEventsError,
  formatCents,
  isForbidden,
  useAdminPaymentSettlement,
  useAdminWebhookEvents,
  type SettlementSummary,
  type WebhookEventEntry,
} from '../api/admin';
import { useAuthStore } from '../store/useAuthStore';

/**
 * Admin payments — /admin/payments (backlog #141), the frontend for
 * GET /api/admin/payments/settlement (#134).
 *
 * Authorization is honest about its layers, mirroring /admin/audit-log
 * (#120): the nav entry and route are hidden from non-ADMIN sessions
 * (client-side only — a non-admin session bounces home and fires no
 * request), but the backend's @PreAuthorize is the real gate. A 403 from
 * the API renders an honest not-authorized state, never fake totals.
 *
 * The figures are the backend's mock-PSP settlement summary, reconciled
 * from the orders table: captured sums orders carrying a capture id (a
 * refunded order was still captured), refunded sums REFUNDED orders, net
 * is the difference, alongside per-status counts. An optional From/To
 * date window (backlog #155) scopes every figure to orders created in
 * the window; blank dates keep the all-time view. The page labels them
 * as what they are — mock-PSP derived figures; no real money has moved
 * and no PSP settlement file was imported.
 *
 * Below the summary, the webhook-event viewer (backlog #156) lists the
 * webhook_event ledger (#140) newest first with an outcome filter, in
 * the AdminAuditLogPage pattern: each row shows the PSP event id, its
 * type, the PSP reference it carried, the outcome as a chip and the
 * received-at time. The ledger stores no raw body or signature, so the
 * section can show neither — the PSP reference is the join key against
 * the orders table.
 */
export default function AdminPaymentsPage() {
  const user = useAuthStore((s) => s.user);
  const isAdmin = user !== null && user.roles.includes('ADMIN');

  // Date window (backlog #155): blank means all time, so the default view
  // is exactly the pre-#155 page. The backend window is [from, to) over
  // order createdAt; the To input is a calendar date the admin means to
  // include, so it is sent as the following day's start (exclusive bound).
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const window = {
    ...(fromDate !== '' ? { from: `${fromDate}T00:00:00Z` } : {}),
    ...(toDate !== ''
      ? {
          to: (() => {
            const day = new Date(`${toDate}T00:00:00.000Z`);
            day.setUTCDate(day.getUTCDate() + 1);
            return day.toISOString().replace('.000Z', 'Z');
          })(),
        }
      : {}),
  };

  const settlement = useAdminPaymentSettlement(isAdmin, window);

  // Webhook-event viewer (backlog #156): paginated newest first; a new
  // outcome filter restarts from the first page.
  const [webhookPage, setWebhookPage] = useState(0);
  const [webhookOutcome, setWebhookOutcome] = useState('');
  const webhooks = useAdminWebhookEvents(webhookPage, webhookOutcome, isAdmin);

  if (!isAdmin) {
    return <Navigate to="/" replace />;
  }

  if (settlement.isError && isForbidden(settlement.error)) {
    return (
      <div>
        <h1 className="mb-4 text-2xl font-bold">Admin payments</h1>
        <p role="alert" className="rounded-lg bg-white p-6 text-center text-neutral-600 shadow-sm">
          You are not authorized to view this page. The payment settlement summary is
          limited to ADMIN accounts, and the server refused this request.
        </p>
      </div>
    );
  }

  const data: SettlementSummary | undefined = settlement.data;
  // Render only a well-formed summary — a malformed payload shows the
  // error/empty path rather than printing NaN totals (mirrors the
  // seller sales-summary guard).
  const showSummary =
    data != null &&
    Number.isFinite(data.capturedCents) &&
    Number.isFinite(data.refundedCents) &&
    Number.isFinite(data.netCents);

  const webhookRows: WebhookEventEntry[] = webhooks.data?.content ?? [];
  const webhookTotalPages = webhooks.data?.totalPages ?? 0;

  const statusCounts: Array<{ label: string; count: number | undefined }> = [
    { label: 'Total orders', count: data?.totalOrders },
    { label: 'Pending', count: data?.pendingCount },
    { label: 'Paid', count: data?.paidCount },
    { label: 'Completed', count: data?.completedCount },
    { label: 'Cancelled', count: data?.cancelledCount },
    { label: 'Refunded', count: data?.refundedCount },
  ];

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">Admin payments</h1>
      <p className="mb-4 text-sm text-neutral-600">
        Mock-PSP settlement summary, derived from the orders table. These are demo
        figures only — no real money has moved and no PSP settlement file was imported.
      </p>
      <section aria-label="Settlement date window" className="mb-4 flex flex-wrap items-end gap-3 rounded-lg bg-white p-4 shadow-sm">
        <div>
          <label htmlFor="settlement-from" className="mb-1 block text-sm text-neutral-500">
            From
          </label>
          <input
            id="settlement-from"
            type="date"
            value={fromDate}
            onChange={(e) => setFromDate(e.target.value)}
            className="rounded border border-neutral-300 px-2 py-1"
          />
        </div>
        <div>
          <label htmlFor="settlement-to" className="mb-1 block text-sm text-neutral-500">
            To
          </label>
          <input
            id="settlement-to"
            type="date"
            value={toDate}
            onChange={(e) => setToDate(e.target.value)}
            className="rounded border border-neutral-300 px-2 py-1"
          />
        </div>
        {(fromDate !== '' || toDate !== '') && (
          <button
            type="button"
            onClick={() => {
              setFromDate('');
              setToDate('');
            }}
            className="rounded border border-neutral-300 px-3 py-1 text-sm"
          >
            Clear dates (all time)
          </button>
        )}
        <p className="text-sm text-neutral-500">
          Blank dates mean all time. The window covers orders created in the range; the To
          date itself is included.
        </p>
      </section>
      {settlement.isLoading && <p>Loading settlement summary…</p>}
      {settlement.isError && (
        <p role="alert" className="text-red-600">
          {describeSettlementError(settlement.error)}
        </p>
      )}
      {showSummary && (
        <>
          <section aria-label="Settlement totals" className="mb-4 grid gap-3 sm:grid-cols-3">
            <div className="rounded-lg bg-white p-4 shadow-sm">
              <p className="text-sm text-neutral-500">Captured</p>
              <p className="mt-1 text-xl font-semibold">{formatCents(data.capturedCents)}</p>
            </div>
            <div className="rounded-lg bg-white p-4 shadow-sm">
              <p className="text-sm text-neutral-500">Refunded</p>
              <p className="mt-1 text-xl font-semibold">{formatCents(data.refundedCents)}</p>
            </div>
            <div className="rounded-lg bg-white p-4 shadow-sm">
              <p className="text-sm text-neutral-500">Net</p>
              <p className="mt-1 text-xl font-semibold">{formatCents(data.netCents)}</p>
            </div>
          </section>
          <section aria-label="Orders by status" className="rounded-lg bg-white p-4 shadow-sm">
            <h2 className="mb-3 font-semibold">Orders by status</h2>
            <dl className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
              {statusCounts.map(({ label, count }) => (
                <div key={label} className="flex items-center justify-between gap-3">
                  <dt className="text-sm text-neutral-500">{label}</dt>
                  <dd className="font-medium">{count}</dd>
                </div>
              ))}
            </dl>
          </section>
        </>
      )}
      <section aria-label="Webhook events" className="mt-6">
        <h2 className="mb-3 text-lg font-semibold">Webhook events</h2>
        <p className="mb-4 text-sm text-neutral-600">
          Mock-PSP deliveries recorded in the webhook ledger, newest first. The PSP
          reference is the capture or refund id the delivery carried; the raw delivery
          body and its signature are not stored, so they are not shown here.
        </p>
        <div className="mb-4">
          <label htmlFor="webhook-outcome" className="sr-only">
            Filter by outcome
          </label>
          <select
            id="webhook-outcome"
            aria-label="Filter by outcome"
            value={webhookOutcome}
            onChange={(e) => {
              setWebhookOutcome(e.target.value);
              setWebhookPage(0);
            }}
            className="rounded-lg border border-neutral-300 bg-white px-4 py-2 shadow-sm focus:border-sky-500 focus:outline-none"
          >
            <option value="">All outcomes</option>
            {WEBHOOK_OUTCOMES.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </div>
        {webhooks.isLoading && <p>Loading webhook events…</p>}
        {webhooks.isError && (
          <p className="text-red-600">{describeWebhookEventsError(webhooks.error)}</p>
        )}
        {webhooks.isSuccess && webhookRows.length === 0 && (
          <div className="rounded-lg bg-white p-6 text-center shadow-sm">
            <p className="text-neutral-600">
              {webhookOutcome !== ''
                ? 'No webhook events match this filter.'
                : 'No webhook events yet.'}
            </p>
          </div>
        )}
        {webhookRows.length > 0 && (
          <>
            <div className="overflow-x-auto rounded-lg bg-white shadow-sm">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-neutral-200 text-neutral-500">
                    <th scope="col" className="px-4 py-3 font-medium">Event</th>
                    <th scope="col" className="px-4 py-3 font-medium">Type</th>
                    <th scope="col" className="px-4 py-3 font-medium">PSP reference</th>
                    <th scope="col" className="px-4 py-3 font-medium">Outcome</th>
                    <th scope="col" className="px-4 py-3 font-medium">Received</th>
                  </tr>
                </thead>
                <tbody>
                  {webhookRows.map((row) => (
                    <tr key={row.id} className="border-b border-neutral-100 last:border-0">
                      <td className="px-4 py-3 font-medium">{row.eventId}</td>
                      <td className="px-4 py-3">{row.eventType}</td>
                      <td className="px-4 py-3">{row.pspReference ?? '—'}</td>
                      <td className="px-4 py-3">
                        <span
                          className={
                            row.outcome === 'MATCHED'
                              ? 'rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-800'
                              : 'rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800'
                          }
                        >
                          {row.outcome}
                        </span>
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
                onClick={() => setWebhookPage((p) => Math.max(0, p - 1))}
                disabled={webhookPage <= 0 || webhooks.isFetching}
                className="rounded bg-neutral-800 px-4 py-1.5 text-sm font-medium text-white hover:bg-neutral-700 disabled:opacity-50"
              >
                Previous
              </button>
              <span className="text-sm text-neutral-600">
                Page {webhookTotalPages === 0 ? 0 : webhookPage + 1} of {webhookTotalPages}
              </span>
              <button
                type="button"
                onClick={() => setWebhookPage((p) => p + 1)}
                disabled={webhookPage + 1 >= webhookTotalPages || webhooks.isFetching}
                className="rounded bg-neutral-800 px-4 py-1.5 text-sm font-medium text-white hover:bg-neutral-700 disabled:opacity-50"
              >
                Next
              </button>
            </div>
          </>
        )}
      </section>
    </div>
  );
}
