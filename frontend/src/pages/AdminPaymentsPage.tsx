import { useState } from 'react';
import { Navigate } from 'react-router-dom';
import {
  PAYOUT_STATUSES,
  PAYMENT_ATTEMPT_OUTCOMES,
  WEBHOOK_OUTCOMES,
  describeAdminPayoutsError,
  describeMarkPayoutPaidError,
  describePaymentAttemptsError,
  describeSettlementError,
  describeWebhookEventsError,
  describeWebhookReplayError,
  formatCents,
  isForbidden,
  useAdminPaymentAttempts,
  useAdminPaymentSettlement,
  useAdminPayouts,
  useAdminWebhookEvents,
  useMarkPayoutPaid,
  useReplayWebhookEvent,
  type AdminPayoutEntry,
  type PaymentAttemptEntry,
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
 * the orders table. Mismatched rows carry a Replay button (backlog
 * #358) that re-runs the recorded event through POST
 * /api/admin/payments/webhooks/{id}/replay (#170); an already-matched
 * event is a recorded no-op, so it gets no button.
 *
 * The payment-attempts section (backlog #211) lists the
 * payment_attempt ledger the same way, defaulting to DECLINED attempts
 * — the rows an operator actually chases ("which orders keep failing
 * to pay?"). Each row shows the order, the outcome as a chip, the
 * order-scoped PSP idempotency key the attempt ran under and the
 * attempt time. The buyer's payment token is never stored in the
 * ledger, so the section can show none.
 *
 * The payout-ledger section (backlog #405) lists every seller's
 * settlement rows from GET /api/admin/payouts, defaulting to PAYABLE —
 * the operator's worklist. Each row shows the order, the seller, the
 * net owed and the payable-at time; PAYABLE rows carry a "Mark paid"
 * button (backlog #390) that settles the row by hand outside the
 * hourly sweep, behind a confirm step, with 422 mapped to "not
 * payable yet". Before #405 the mark-paid endpoint had no UI at all —
 * the section exists because the endpoint alone gave an operator
 * nothing to click.
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
  // Named dateWindow, not window: a local `window` would shadow the
  // browser global, and the payout-ledger confirm below calls
  // window.confirm — the shadow made that call throw (caught by the
  // #405 page tests; the pre-#405 page never called window.confirm).
  const dateWindow = {
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

  const settlement = useAdminPaymentSettlement(isAdmin, dateWindow);

  // Webhook-event viewer (backlog #156): paginated newest first; a new
  // outcome filter restarts from the first page.
  const [webhookPage, setWebhookPage] = useState(0);
  const [webhookOutcome, setWebhookOutcome] = useState('');
  const webhooks = useAdminWebhookEvents(webhookPage, webhookOutcome, isAdmin);

  // Webhook replay (backlog #358): one mutation shared by every row;
  // `replayingId` pins the pending state to the clicked row, and
  // `replayFeedback` carries the last outcome (success text or the
  // envelope error) until the next replay attempt replaces it.
  const replay = useReplayWebhookEvent();
  const [replayingId, setReplayingId] = useState<number | null>(null);
  const [replayFeedback, setReplayFeedback] = useState<{
    kind: 'ok' | 'error';
    text: string;
  } | null>(null);

  // Payment-attempt viewer (backlog #211): paginated newest first,
  // defaulting to DECLINED attempts; a new outcome filter restarts
  // from the first page, mirroring the webhook viewer above.
  const [attemptPage, setAttemptPage] = useState(0);
  const [attemptOutcome, setAttemptOutcome] = useState('DECLINED');
  const attempts = useAdminPaymentAttempts(attemptPage, attemptOutcome, isAdmin);

  // Payout ledger (backlog #405): the ADMIN read of the seller payout
  // ledger (GET /api/admin/payouts), defaulting to PAYABLE — the
  // operator's worklist. A new status filter restarts from the first
  // page, mirroring the viewers above. The mark-paid mutation (backlog
  // #390) is one shared mutation; `markingId` pins the pending state
  // to the clicked row, and `markPaidFeedback` carries the last outcome
  // until the next attempt replaces it.
  const [payoutPage, setPayoutPage] = useState(0);
  const [payoutStatus, setPayoutStatus] = useState('PAYABLE');
  const payouts = useAdminPayouts(payoutPage, payoutStatus, isAdmin);
  const markPaid = useMarkPayoutPaid();
  const [markingId, setMarkingId] = useState<number | null>(null);
  const [markPaidFeedback, setMarkPaidFeedback] = useState<{
    kind: 'ok' | 'error';
    text: string;
  } | null>(null);

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

  const attemptRows: PaymentAttemptEntry[] = attempts.data?.content ?? [];
  const attemptTotalPages = attempts.data?.totalPages ?? 0;

  const payoutRows: AdminPayoutEntry[] = payouts.data?.content ?? [];
  const payoutTotalPages = payouts.data?.totalPages ?? 0;

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
      <section aria-label="Payout ledger" className="mt-6">
        <h2 className="mb-3 text-lg font-semibold">Payout ledger</h2>
        <p className="mb-4 text-sm text-neutral-600">
          Every seller&apos;s settlement rows, newest first — the operator&apos;s
          view of the payout ledger the seller pages show one seller at a
          time. PAYABLE is the worklist: a row the hourly sweep has not
          paid yet. Marking one paid settles it by hand outside the sweep,
          with the same guards and the same seller notification; a PAID row
          is a ledger state — the mock pays nobody, so no bank transfer
          sits behind it.
        </p>
        <div className="mb-4">
          <label htmlFor="payout-status" className="sr-only">
            Filter payouts by status
          </label>
          <select
            id="payout-status"
            aria-label="Filter payouts by status"
            value={payoutStatus}
            onChange={(e) => {
              setPayoutStatus(e.target.value);
              setPayoutPage(0);
            }}
            className="rounded-lg border border-neutral-300 bg-white px-4 py-2 shadow-sm focus:border-sky-500 focus:outline-none"
          >
            {PAYOUT_STATUSES.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </div>
        {payouts.isLoading && <p>Loading payout ledger…</p>}
        {payouts.isError && (
          <p className="text-red-600">{describeAdminPayoutsError(payouts.error)}</p>
        )}
        {markPaidFeedback !== null &&
          (markPaidFeedback.kind === 'ok' ? (
            <p role="status" className="mb-4 text-sm text-emerald-700">
              {markPaidFeedback.text}
            </p>
          ) : (
            <p role="alert" className="mb-4 text-sm text-red-600">
              {markPaidFeedback.text}
            </p>
          ))}
        {payouts.isSuccess && payoutRows.length === 0 && (
          <div className="rounded-lg bg-white p-6 text-center shadow-sm">
            <p className="text-neutral-600">
              {payoutStatus === 'PAYABLE'
                ? 'No payable payouts yet.'
                : 'No payouts match this filter.'}
            </p>
          </div>
        )}
        {payoutRows.length > 0 && (
          <>
            <div className="overflow-x-auto rounded-lg bg-white shadow-sm">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-neutral-200 text-neutral-500">
                    <th scope="col" className="px-4 py-3 font-medium">Order</th>
                    <th scope="col" className="px-4 py-3 font-medium">Seller</th>
                    <th scope="col" className="px-4 py-3 font-medium">Net</th>
                    <th scope="col" className="px-4 py-3 font-medium">Status</th>
                    <th scope="col" className="px-4 py-3 font-medium">Payable at</th>
                    <th scope="col" className="px-4 py-3 font-medium">
                      <span className="sr-only">Mark paid</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {payoutRows.map((row) => (
                    <tr key={row.id} className="border-b border-neutral-100 last:border-0">
                      <td className="px-4 py-3 font-medium">#{row.orderId}</td>
                      <td className="px-4 py-3">Seller #{row.sellerId}</td>
                      <td className="px-4 py-3">{formatCents(row.netCents)}</td>
                      <td className="px-4 py-3">
                        <span
                          className={
                            row.status === 'PAID'
                              ? 'rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-800'
                              : row.status === 'PAYABLE'
                                ? 'rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800'
                                : 'rounded-full bg-neutral-100 px-2 py-0.5 text-xs font-medium text-neutral-700'
                          }
                        >
                          {row.status}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        {row.payableAt !== null
                          ? new Date(row.payableAt).toLocaleString()
                          : '—'}
                      </td>
                      <td className="px-4 py-3">
                        {row.status === 'PAYABLE' && (
                          <button
                            type="button"
                            aria-label={`Mark payout ${row.id} paid`}
                            disabled={markPaid.isLoading && markingId === row.id}
                            onClick={() => {
                              if (
                                !window.confirm(
                                  `Mark payout #${row.id} (order #${row.orderId}, ${formatCents(row.netCents)}) as paid?`,
                                )
                              ) {
                                return;
                              }
                              setMarkingId(row.id);
                              setMarkPaidFeedback(null);
                              markPaid.mutate(row.id, {
                                onSuccess: (ack) => {
                                  setMarkPaidFeedback({
                                    kind: 'ok',
                                    text: `Marked payout #${ack.id} as paid.`,
                                  });
                                  setMarkingId(null);
                                },
                                onError: (error) => {
                                  setMarkPaidFeedback({
                                    kind: 'error',
                                    text: describeMarkPayoutPaidError(error),
                                  });
                                  setMarkingId(null);
                                },
                              });
                            }}
                            className="rounded border border-neutral-300 px-3 py-1 text-sm hover:bg-neutral-50 disabled:opacity-50"
                          >
                            {markPaid.isLoading && markingId === row.id
                              ? 'Marking…'
                              : 'Mark paid'}
                          </button>
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
                onClick={() => setPayoutPage((p) => Math.max(0, p - 1))}
                disabled={payoutPage <= 0 || payouts.isFetching}
                className="rounded bg-neutral-800 px-4 py-1.5 text-sm font-medium text-white hover:bg-neutral-700 disabled:opacity-50"
              >
                Previous
              </button>
              <span className="text-sm text-neutral-600">
                Page {payoutTotalPages === 0 ? 0 : payoutPage + 1} of {payoutTotalPages}
              </span>
              <button
                type="button"
                onClick={() => setPayoutPage((p) => p + 1)}
                disabled={payoutPage + 1 >= payoutTotalPages || payouts.isFetching}
                className="rounded bg-neutral-800 px-4 py-1.5 text-sm font-medium text-white hover:bg-neutral-700 disabled:opacity-50"
              >
                Next
              </button>
            </div>
          </>
        )}
      </section>
      <section aria-label="Webhook events" className="mt-6">
        <h2 className="mb-3 text-lg font-semibold">Webhook events</h2>
        <p className="mb-4 text-sm text-neutral-600">
          Mock-PSP deliveries recorded in the webhook ledger, newest first. The PSP
          reference is the capture or refund id the delivery carried; the raw delivery
          body and its signature are not stored, so they are not shown here.
          Mismatched events can be replayed from this table — an already-matched
          event was applied once already, so it has no replay button.
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
        {replayFeedback !== null &&
          (replayFeedback.kind === 'ok' ? (
            <p role="status" className="mb-4 text-sm text-emerald-700">
              {replayFeedback.text}
            </p>
          ) : (
            <p role="alert" className="mb-4 text-sm text-red-600">
              {replayFeedback.text}
            </p>
          ))}
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
                    <th scope="col" className="px-4 py-3 font-medium">
                      <span className="sr-only">Replay</span>
                    </th>
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
                      <td className="px-4 py-3">
                        {row.outcome !== 'MATCHED' && (
                          <button
                            type="button"
                            aria-label={`Replay ${row.eventId}`}
                            disabled={replay.isLoading && replayingId === row.id}
                            onClick={() => {
                              setReplayingId(row.id);
                              setReplayFeedback(null);
                              replay.mutate(row.id, {
                                onSuccess: (ack) => {
                                  setReplayFeedback({
                                    kind: 'ok',
                                    text: ack.applied
                                      ? `Replayed ${ack.eventId} — it now matches and was applied once.`
                                      : `Replayed ${ack.eventId} — it still mismatches; nothing was applied.`,
                                  });
                                  setReplayingId(null);
                                },
                                onError: (error) => {
                                  setReplayFeedback({
                                    kind: 'error',
                                    text: describeWebhookReplayError(error),
                                  });
                                  setReplayingId(null);
                                },
                              });
                            }}
                            className="rounded border border-neutral-300 px-3 py-1 text-sm hover:bg-neutral-50 disabled:opacity-50"
                          >
                            {replay.isLoading && replayingId === row.id
                              ? 'Replaying…'
                              : 'Replay'}
                          </button>
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
      <section aria-label="Payment attempts" className="mt-6">
        <h2 className="mb-3 text-lg font-semibold">Payment attempts</h2>
        <p className="mb-4 text-sm text-neutral-600">
          Capture attempts recorded in the payment-attempt ledger, newest first —
          declined attempts by default, so the orders whose buyers keep failing to
          pay are visible at a glance. The idempotency key is the order-scoped key
          the attempt ran under; the buyer&apos;s payment token is not stored, so
          it is not shown here. REFUNDED rows carry the direct-refund reason
          (backlog #239); capture rows show no reason.
        </p>
        <div className="mb-4">
          <label htmlFor="attempt-outcome" className="sr-only">
            Filter by attempt outcome
          </label>
          <select
            id="attempt-outcome"
            aria-label="Filter by attempt outcome"
            value={attemptOutcome}
            onChange={(e) => {
              setAttemptOutcome(e.target.value);
              setAttemptPage(0);
            }}
            className="rounded-lg border border-neutral-300 bg-white px-4 py-2 shadow-sm focus:border-sky-500 focus:outline-none"
          >
            <option value="">All outcomes</option>
            {PAYMENT_ATTEMPT_OUTCOMES.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </div>
        {attempts.isLoading && <p>Loading payment attempts…</p>}
        {attempts.isError && (
          <p className="text-red-600">{describePaymentAttemptsError(attempts.error)}</p>
        )}
        {attempts.isSuccess && attemptRows.length === 0 && (
          <div className="rounded-lg bg-white p-6 text-center shadow-sm">
            <p className="text-neutral-600">
              {attemptOutcome !== ''
                ? 'No payment attempts match this filter.'
                : 'No payment attempts yet.'}
            </p>
          </div>
        )}
        {attemptRows.length > 0 && (
          <>
            <div className="overflow-x-auto rounded-lg bg-white shadow-sm">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-neutral-200 text-neutral-500">
                    <th scope="col" className="px-4 py-3 font-medium">Order</th>
                    <th scope="col" className="px-4 py-3 font-medium">Outcome</th>
                    <th scope="col" className="px-4 py-3 font-medium">Idempotency key</th>
                    <th scope="col" className="px-4 py-3 font-medium">Refund reason</th>
                    <th scope="col" className="px-4 py-3 font-medium">Attempted</th>
                  </tr>
                </thead>
                <tbody>
                  {attemptRows.map((row) => (
                    <tr key={row.id} className="border-b border-neutral-100 last:border-0">
                      <td className="px-4 py-3 font-medium">#{row.orderId}</td>
                      <td className="px-4 py-3">
                        <span
                          className={
                            row.outcome === 'SUCCEEDED'
                              ? 'rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-800'
                              : 'rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-800'
                          }
                        >
                          {row.outcome}
                        </span>
                      </td>
                      <td className="px-4 py-3">{row.idempotencyKey ?? '—'}</td>
                      <td className="px-4 py-3">{row.refundReason ?? '—'}</td>
                      <td className="px-4 py-3">{new Date(row.createdAt).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="mt-4 flex items-center gap-3">
              <button
                type="button"
                onClick={() => setAttemptPage((p) => Math.max(0, p - 1))}
                disabled={attemptPage <= 0 || attempts.isFetching}
                className="rounded bg-neutral-800 px-4 py-1.5 text-sm font-medium text-white hover:bg-neutral-700 disabled:opacity-50"
              >
                Previous
              </button>
              <span className="text-sm text-neutral-600">
                Page {attemptTotalPages === 0 ? 0 : attemptPage + 1} of {attemptTotalPages}
              </span>
              <button
                type="button"
                onClick={() => setAttemptPage((p) => p + 1)}
                disabled={attemptPage + 1 >= attemptTotalPages || attempts.isFetching}
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
