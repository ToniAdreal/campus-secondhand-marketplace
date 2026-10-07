import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { describeCancelError, describePayError, useCancelOrder, useOrder, usePayOrder } from '../api/orders';
import { useAuthStore } from '../store/useAuthStore';

/**
 * Order confirmation — shown right after a successful buy-now. Reads the
 * order through the buyer order-reads endpoint (GET /api/orders/{id}) and
 * wires the mock capture endpoint (POST /api/orders/{id}/pay) behind an
 * honest "Pay now (demo)" button — no real money moves.
 */
export default function OrderConfirmationPage() {
  const { id } = useParams();
  const user = useAuthStore((s) => s.user);
  const { data, isLoading, isError } = useOrder(Number(id));
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const payOrder = usePayOrder();
  const cancelOrder = useCancelOrder();

  if (isLoading) return <p>Loading…</p>;
  if (isError || !data) return <p className="text-red-600">Order not found.</p>;

  const orderId = Number(id);
  const isBuyer = user !== null && user.id === data.buyerId;
  const canPay = user !== null && data.status === 'PENDING';
  const canCancel = isBuyer && data.status === 'PENDING';
  const paid = data.status === 'PAID';
  const cancelled = data.status === 'CANCELLED';

  const handlePay = () => {
    setErrorMessage(null);
    // The server's already-PAID idempotency makes a retry safe: the same
    // click can never trigger a second capture.
    payOrder.mutate(orderId, {
      onError: (error) => setErrorMessage(describePayError(error)),
    });
  };

  const handleCancel = () => {
    setErrorMessage(null);
    // Irreversible from the buyer's side (the listing goes back on sale),
    // so ask before sending. The server's re-cancel idempotency makes a
    // double-click safe — it just returns the already-cancelled order.
    if (
      !window.confirm(
        'Cancel this order? The listing will become available for other buyers again.',
      )
    ) {
      return;
    }
    cancelOrder.mutate(orderId, {
      onError: (error) => setErrorMessage(describeCancelError(error)),
    });
  };

  return (
    <section className="rounded-lg bg-white p-6 shadow-sm">
      <h1 className="text-2xl font-bold">Order confirmed</h1>
      <p className="mt-2 text-neutral-600">
        Order #{data.id} is {data.status.toLowerCase()}.
        {data.status === 'PENDING' && ' The listing is now reserved for you.'}
        {cancelled && ' The listing has been released for other buyers.'}
      </p>
      <dl className="mt-4 space-y-1 text-sm">
        <div className="flex gap-2">
          <dt className="text-neutral-500">Amount:</dt>
          <dd>¥{(data.amountCents / 100).toFixed(2)}</dd>
        </div>
        <div className="flex gap-2">
          <dt className="text-neutral-500">Status:</dt>
          <dd>{data.status}</dd>
        </div>
      </dl>
      {canPay && (
        <div className="mt-4">
          <button
            type="button"
            onClick={handlePay}
            disabled={payOrder.isLoading}
            className="rounded bg-green-600 px-5 py-2 font-medium text-white hover:bg-green-700 disabled:opacity-50"
          >
            {payOrder.isLoading ? 'Paying…' : 'Pay now (demo)'}
          </button>
          <p className="mt-2 text-sm text-neutral-500">
            Portfolio-demo note: this is a mock capture — no real money
            moves. The button is safe to press twice; the backend returns the
            already-paid order instead of charging again.
          </p>
        </div>
      )}
      {paid && (
        <p className="mt-4 text-sm text-green-700">
          Paid (demo capture) — the seller will now confirm the handoff.
        </p>
      )}
      {canCancel && (
        <div className="mt-4">
          <button
            type="button"
            onClick={handleCancel}
            disabled={cancelOrder.isLoading}
            className="rounded border border-red-600 px-5 py-2 font-medium text-red-600 hover:bg-red-50 disabled:opacity-50"
          >
            {cancelOrder.isLoading ? 'Cancelling…' : 'Cancel order'}
          </button>
          <p className="mt-2 text-sm text-neutral-500">
            Cancelling releases the listing so other buyers can grab it.
          </p>
        </div>
      )}
      {cancelled && (
        <p className="mt-4 text-sm text-neutral-600">
          This order was cancelled — the listing is available again.
        </p>
      )}
      {errorMessage && (
        <p role="alert" className="mt-2 text-sm text-red-600">
          {errorMessage}
        </p>
      )}
      <Link to={`/items/${data.itemId}`} className="mt-4 inline-block text-sm text-blue-600 hover:underline">
        Back to the listing
      </Link>
    </section>
  );
}
