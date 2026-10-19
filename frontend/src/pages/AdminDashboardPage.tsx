import { useState } from 'react';
import { Navigate } from 'react-router-dom';
import {
  DASHBOARD_WINDOWS,
  describeDashboardError,
  isForbidden,
  useAdminDashboard,
  type AdminDashboard,
  type DashboardWindow,
} from '../api/admin';
import { useAuthStore } from '../store/useAuthStore';

const WINDOW_LABELS: Record<DashboardWindow, string> = {
  '24h': 'Last 24 hours',
  '7d': 'Last 7 days',
  '30d': 'Last 30 days',
};

/**
 * Admin operations dashboard — /admin/dashboard (backlog #196), the
 * frontend for GET /api/admin/dashboard.
 *
 * Authorization mirrors the other admin pages (#141): the nav entry
 * and route are hidden from non-ADMIN sessions (client-side only — a
 * non-admin session bounces home and fires no request), but the
 * backend's @PreAuthorize is the real gate. A 403 from the API renders
 * an honest not-authorized state, never fake counts.
 *
 * The figures are aggregate counts derived from the database tables
 * (see the backend DashboardDto): the windowed sections count rows
 * created inside the selected look-back window, while the "right now"
 * figures (available listings, open reports, open disputes) are
 * current snapshots the window does not change. There is no declines
 * figure — a declined capture leaves no durable row, so the dashboard
 * does not estimate one; declines live on the payments.declined meter.
 */
export default function AdminDashboardPage() {
  const user = useAuthStore((s) => s.user);
  const isAdmin = user !== null && user.roles.includes('ADMIN');
  const [window, setWindow] = useState<DashboardWindow>('24h');
  const dashboard = useAdminDashboard(window, isAdmin);

  if (!isAdmin) {
    return <Navigate to="/" replace />;
  }

  if (dashboard.isError && isForbidden(dashboard.error)) {
    return (
      <div>
        <h1 className="mb-4 text-2xl font-bold">Admin dashboard</h1>
        <p role="alert" className="rounded-lg bg-white p-6 text-center text-neutral-600 shadow-sm">
          You are not authorized to view this page. The operations dashboard is
          limited to ADMIN accounts, and the server refused this request.
        </p>
      </div>
    );
  }

  const data: AdminDashboard | undefined = dashboard.data;
  const showData = data != null && Number.isFinite(data.usersRegistered);

  const activityRows: Array<{ label: string; count: number | undefined }> = [
    { label: 'Users registered', count: data?.usersRegistered },
    { label: 'Listings created', count: data?.listingsCreated },
    { label: 'Listings expired', count: data?.listingsExpiredInWindow },
    { label: 'Orders created', count: data?.ordersTotalInWindow },
    { label: 'Captures', count: data?.captures },
    { label: 'Refunds', count: data?.refunds },
    { label: 'Webhook deliveries matched', count: data?.webhooksMatched },
    { label: 'Webhook deliveries mismatched', count: data?.webhooksMismatched },
  ];
  const orderRows: Array<{ label: string; count: number | undefined }> = [
    { label: 'Pending', count: data?.ordersPending },
    { label: 'Paid', count: data?.ordersPaid },
    { label: 'Completed', count: data?.ordersCompleted },
    { label: 'Cancelled', count: data?.ordersCancelled },
    { label: 'Refunded', count: data?.ordersRefunded },
    { label: 'Disputed', count: data?.ordersDisputed },
  ];
  const nowRows: Array<{ label: string; count: number | undefined }> = [
    { label: 'Listings available now', count: data?.listingsAvailableNow },
    { label: 'Open reports', count: data?.openReports },
    { label: 'Open disputes', count: data?.openDisputes },
  ];

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">Admin dashboard</h1>
      <p className="mb-4 text-sm text-neutral-600">
        Marketplace health at a glance, counted from the database tables. Windowed
        figures cover rows created in the selected window; “right now” figures are
        current totals the window does not change. Declined captures are not shown:
        a decline leaves no durable record, so there is nothing honest to count.
      </p>

      <section aria-label="Dashboard window" className="mb-4 flex flex-wrap gap-2">
        {DASHBOARD_WINDOWS.map((w) => (
          <button
            key={w}
            type="button"
            aria-pressed={window === w}
            onClick={() => setWindow(w)}
            className={
              window === w
                ? 'rounded-full bg-neutral-900 px-3 py-1 text-sm font-semibold text-white'
                : 'rounded-full bg-white px-3 py-1 text-sm text-neutral-700 shadow-sm'
            }
          >
            {WINDOW_LABELS[w]}
          </button>
        ))}
      </section>

      {dashboard.isError && !isForbidden(dashboard.error) && (
        <p role="alert" className="mb-4 rounded-lg bg-white p-6 text-center text-neutral-600 shadow-sm">
          {describeDashboardError(dashboard.error)}
        </p>
      )}

      {showData && (
        <div className="grid gap-4 md:grid-cols-3">
          <section aria-label="Activity in window" className="rounded-lg bg-white p-4 shadow-sm">
            <h2 className="mb-2 text-lg font-semibold">Activity in window</h2>
            <dl>
              {activityRows.map((row) => (
                <div key={row.label} className="flex justify-between py-1 text-sm">
                  <dt className="text-neutral-600">{row.label}</dt>
                  <dd className="font-semibold">{row.count}</dd>
                </div>
              ))}
            </dl>
          </section>
          <section aria-label="Orders in window by status" className="rounded-lg bg-white p-4 shadow-sm">
            <h2 className="mb-2 text-lg font-semibold">Orders in window by status</h2>
            <dl>
              {orderRows.map((row) => (
                <div key={row.label} className="flex justify-between py-1 text-sm">
                  <dt className="text-neutral-600">{row.label}</dt>
                  <dd className="font-semibold">{row.count}</dd>
                </div>
              ))}
            </dl>
          </section>
          <section aria-label="Right now" className="rounded-lg bg-white p-4 shadow-sm">
            <h2 className="mb-2 text-lg font-semibold">Right now</h2>
            <dl>
              {nowRows.map((row) => (
                <div key={row.label} className="flex justify-between py-1 text-sm">
                  <dt className="text-neutral-600">{row.label}</dt>
                  <dd className="font-semibold">{row.count}</dd>
                </div>
              ))}
            </dl>
          </section>
        </div>
      )}
    </div>
  );
}
