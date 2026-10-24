import { useState } from 'react';
import {
  describeConfirmReceiptError,
  useConfirmReceiptOrder,
  type Order,
} from '../api/orders';

/**
 * Buyer-side shipped view (backlog #222): renders only for SHIPPED
 * orders. Shows the seller-entered carrier and tracking number
 * verbatim — no carrier integration exists, so there is no delivery
 * status to show, only the number the seller typed — plus the
 * "Confirm receipt" button that closes the shipped path
 * (SHIPPED → COMPLETED, listing RESERVED → SOLD). Used by both buyer
 * surfaces (MyOrdersPage rows and OrderConfirmationPage) so the two
 * can never drift apart.
 *
 * After a successful confirmation the invalidated order query refetches
 * to COMPLETED and this component unmounts; the local success note
 * covers the gap between the mutation resolving and the refetch
 * landing, so the buyer sees the confirmation stick.
 */
export default function OrderShipmentControl({ order }: { order: Order }) {
  const [error, setError] = useState<string | null>(null);
  const confirmReceipt = useConfirmReceiptOrder();

  if (order.status !== 'SHIPPED') {
    return null;
  }
  if (confirmReceipt.isSuccess) {
    return (
      <p className="mt-2 text-sm text-green-700">
        Receipt confirmed — thanks! The order is now complete.
      </p>
    );
  }
  return (
    <div className="mt-2">
      <p className="text-sm text-neutral-600">
        Shipped via {order.carrier ?? 'unknown carrier'} — tracking{' '}
        {order.trackingNumber ?? 'not provided'}.
      </p>
      <p className="mt-1 text-sm text-neutral-500">
        Confirm receipt once the parcel arrives — that completes the order and marks the
        listing sold.
      </p>
      {error && (
        <p role="alert" className="mt-1 text-sm text-red-600">
          {error}
        </p>
      )}
      <button
        type="button"
        onClick={() => {
          setError(null);
          confirmReceipt.mutate(order.id, {
            onError: (err) => setError(describeConfirmReceiptError(err)),
          });
        }}
        disabled={confirmReceipt.isLoading}
        className="mt-2 rounded bg-green-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-50"
      >
        {confirmReceipt.isLoading ? 'Confirming…' : 'Confirm receipt'}
      </button>
    </div>
  );
}
