import { Navigate } from 'react-router-dom';
import {
  describeSettlementError,
  formatCents,
  isForbidden,
  useAdminPaymentSettlement,
  type SettlementSummary,
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
 * is the difference, alongside per-status counts. The page labels them
 * as what they are — mock-PSP derived figures; no real money has moved
 * and no PSP settlement file was imported.
 */
export default function AdminPaymentsPage() {
  const user = useAuthStore((s) => s.user);
  const isAdmin = user !== null && user.roles.includes('ADMIN');

  const settlement = useAdminPaymentSettlement(isAdmin);

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
    </div>
  );
}
