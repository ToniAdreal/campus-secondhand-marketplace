import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  describeCancelError,
  describePayError,
  describeRegenerateHandoverError,
  useCancelOrder,
  useOrder,
  usePayOrder,
  useRegenerateHandoverCode,
} from '../api/orders';
import OrderPspReferences from '../components/OrderPspReferences';
import OrderReceipt from '../components/OrderReceipt';
import OrderShipmentControl from '../components/OrderShipmentControl';
import { useAuthStore } from '../store/useAuthStore';

/**
 * Order confirmation — shown right after a successful buy-now. Reads the
 * order through the buyer order-reads endpoint (GET /api/orders/{id}) and
 * wires the mock capture endpoint (POST /api/orders/{id}/pay) behind an
 * honest "Pay now (demo)" button — no real money moves. Once paid, the
 * order's mock PSP references render under the amount/status block
 * (OrderPspReferences, backlog #119) — the pay mutation invalidates the
 * orders subtree, so the capture id arrives on the refetch with no extra
 * wiring here.
 *
 * Handover code (backlog #179): the pay response is the ONLY place the
 * buyer's 6-digit code is ever shown (the backend stores just its hash),
 * so it is captured from the mutation result into local state before the
 * invalidation refetch replaces the order. The buyer gives the code to
 * the seller when they receive the item; the seller cannot complete the
 * order without it. A buyer who lost it regenerates a new one here —
 * the old code stops working the moment a new one is minted.
 */
export default function OrderConfirmationPage() {
  const { id } = useParams();
  const user = useAuthStore((s) => s.user);
  const { data, isLoading, isError } = useOrder(Number(id));
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  // The handover code disclosed by the pay / regenerate response — kept
  // in local state because order reads never carry it again (#179).
  const [handoverCode, setHandoverCode] = useState<string | null>(null);
  const payOrder = usePayOrder();
  const cancelOrder = useCancelOrder();
  const regenerateCode = useRegenerateHandoverCode();

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
      onSuccess: (order) => {
        // Capture the one-time disclosure before the invalidation
        // refetch swaps in a read DTO that never carries the code.
        if (order.handoverCode) {
          setHandoverCode(order.handoverCode);
        }
      },
      onError: (error) => setErrorMessage(describePayError(error)),
    });
  };

  const handleRegenerate = () => {
    setErrorMessage(null);
    // Regenerating invalidates the previous code — ask first, mirroring
    // the cancel flow's confirm dialog.
    if (
      !window.confirm(
        'Generate a new handover code? The previous code will stop working.',
      )
    ) {
      return;
    }
    regenerateCode.mutate(orderId, {
      onSuccess: (order) => {
        if (order.handoverCode) {
          setHandoverCode(order.handoverCode);
        }
      },
      onError: (error) => setErrorMessage(describeRegenerateHandoverError(error)),
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
      <div className="mt-2">
        <OrderPspReferences order={data} />
      </div>
      <OrderShipmentControl order={data} />
      <OrderReceipt orderId={data.id} />
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
      {paid && isBuyer && (
        <div className="mt-4 rounded border border-neutral-200 p-4">
          {handoverCode ? (
            <>
              <p className="text-sm">
                Your handover code: <strong className="text-lg tracking-widest">{handoverCode}</strong>
              </p>
              <p className="mt-1 text-sm text-neutral-500">
                Give this code to the seller when you receive the item — they
                need it to complete the order. Save it now: for your security
                it is shown only once and cannot be displayed again.
              </p>
            </>
          ) : (
            <p className="text-sm text-neutral-600">
              Your handover code was shown once when you paid. If you no
              longer have it, generate a new one — the seller needs it to
              complete the order.
            </p>
          )}
          <button
            type="button"
            onClick={handleRegenerate}
            disabled={regenerateCode.isLoading}
            className="mt-2 rounded border border-neutral-600 px-4 py-1.5 text-sm font-medium text-neutral-700 hover:bg-neutral-50 disabled:opacity-50"
          >
            {regenerateCode.isLoading ? 'Generating…' : 'Generate a new handover code'}
          </button>
        </div>
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
