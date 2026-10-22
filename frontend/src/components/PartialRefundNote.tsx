import type { Order } from '../api/orders';

/**
 * Partially-refunded note (backlog #210): renders only while an order
 * carries a cumulative refund strictly between zero and its full
 * amount — a PAID order the seller has partially refunded. A REFUNDED
 * order's refunded total equals its amount, so the status chip already
 * tells that story and this note stays silent; an untouched order has
 * refunded 0 and shows nothing either.
 */
export default function PartialRefundNote({ order }: { order: Order }) {
  const refunded = order.refundedAmountCents ?? 0;
  if (refunded <= 0 || refunded >= order.amountCents) {
    return null;
  }
  return (
    <p className="mt-1 text-sm text-amber-700">
      Partially refunded: ¥{(refunded / 100).toFixed(2)} of ¥{(order.amountCents / 100).toFixed(2)}
    </p>
  );
}
