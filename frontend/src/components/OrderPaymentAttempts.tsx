import { useState } from 'react';
import { useOrderAttempts } from '../api/orders';

/**
 * Buyer payment-attempt history (backlog #335) — the order's
 * payment_attempt rows (backlog #211) on the order confirmation page.
 * Mirrors the receipt component's lazy pattern: the "Show payment
 * attempts" button fetches GET /api/orders/{id}/attempts only when the
 * buyer asks for it, so order pages make no extra fetch by default.
 *
 * Rows render the outcome verbatim (SUCCEEDED / DECLINED / REFUNDED),
 * the attempt time and, on REFUNDED rows, the direct-refund reason —
 * the same fields the ADMIN viewer shows, minus the order-scoped PSP
 * idempotency key, which is an operator correlation id and is not
 * buyer-meaningful. The section carries the same demo honesty as the
 * PSP references: these are mock-PSP attempts, never real processor
 * records. An empty trail renders nothing extra — no rows, no
 * placeholder note — just the toggle back to hidden.
 */
export default function OrderPaymentAttempts({ orderId }: { orderId: number }) {
  const [open, setOpen] = useState(false);
  const attempts = useOrderAttempts(orderId, open);

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="mt-2 text-sm text-blue-600 hover:underline"
      >
        Show payment attempts
      </button>
    );
  }

  const rows = attempts.data?.content ?? [];
  if (attempts.data && rows.length === 0) {
    return (
      <button
        type="button"
        onClick={() => setOpen(false)}
        className="mt-2 text-sm text-blue-600 hover:underline"
      >
        Hide payment attempts
      </button>
    );
  }

  return (
    <div className="mt-3">
      <div className="flex items-center justify-between gap-3">
        <h2 className="font-semibold">Payment attempts (demo)</h2>
        <button
          type="button"
          onClick={() => setOpen(false)}
          className="text-sm text-blue-600 hover:underline"
        >
          Hide payment attempts
        </button>
      </div>
      <p className="mt-1 text-sm text-neutral-500">
        Mock-PSP attempts for this order, newest first — demo records, never
        real processor records.
      </p>
      {attempts.isLoading && <p className="mt-2 text-sm text-neutral-500">Loading payment attempts…</p>}
      {attempts.isError && (
        <p role="alert" className="mt-2 text-sm text-red-600">
          Could not load payment attempts.
        </p>
      )}
      {rows.length > 0 && (
        <ul className="mt-2 space-y-1 text-sm">
          {rows.map((attempt) => (
            <li key={attempt.id} className="flex gap-2">
              <span>{attempt.outcome}</span>
              <span className="text-neutral-500">
                {new Date(attempt.createdAt).toLocaleString()}
              </span>
              {attempt.refundReason && (
                <span className="text-neutral-500">{attempt.refundReason}</span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
