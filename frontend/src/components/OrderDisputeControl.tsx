import { useState } from 'react';
import { describeDisputeError, useOpenDispute } from '../api/disputes';
import type { Order } from '../api/orders';
import { OrderDisputeEvidence } from './DisputeEvidencePanel';

/**
 * Per-order dispute control (backlog #169). PAID, SHIPPED (backlog
 * #222 — a shipped order that never arrives is exactly the dispute
 * case) and COMPLETED rows can be disputed: the button reveals a
 * reason form, and submitting freezes the order in DISPUTED until an
 * ADMIN resolves it. DISPUTED rows show the open state instead, plus
 * the evidence panel (backlog #200) so the opener can attach photos
 * of the problem while it is open.
 *
 * The eligible window (7 days from the PAID/SHIPPED/COMPLETED
 * transition) is enforced by the server — the order shape carries no
 * transition timestamp the SPA could check honestly — so the button
 * renders for every PAID/SHIPPED/COMPLETED row and a window-expired
 * attempt maps the server's 422 to plain copy via describeDisputeError.
 *
 * Shared by MyOrdersPage (buyer rows, #169) and SellerOrdersPage
 * (seller rows, backlog #414 — the #255 backend path already let the
 * listing's seller dispute, the SPA button was buyer-rows only).
 * The same form and the same 422 mapping serve both sides; the
 * server is the party check, so no client-side role branching here.
 */
export default function OrderDisputeControl({ order }: { order: Order }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const openDispute = useOpenDispute();

  if (order.status === 'DISPUTED') {
    return (
      <>
        <p className="mt-2 text-sm text-red-700">
          Dispute open — this order is frozen while an admin reviews it.
        </p>
        <OrderDisputeEvidence orderId={order.id} />
      </>
    );
  }
  if (order.status !== 'PAID' && order.status !== 'SHIPPED' && order.status !== 'COMPLETED') {
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
