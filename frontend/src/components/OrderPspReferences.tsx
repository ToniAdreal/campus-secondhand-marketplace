import type { Order } from '../api/orders';

/**
 * Mock PSP references for an order (backlog #119) — the capture id a paid
 * order carries and the refund id a refunded order carries (backend #114).
 * Shared by the order confirmation page and both order lists (buyer
 * my-orders, seller orders) so the honest "(demo)" labelling can never
 * drift apart between surfaces: these are mock-processor references, not
 * real receipts. Renders nothing while both are null (a PENDING order has
 * no reference yet, and a cancelled order never gets one).
 */
export default function OrderPspReferences({
  order,
}: {
  order: Pick<Order, 'captureId' | 'refundId'>;
}) {
  if (!order.captureId && !order.refundId) return null;
  return (
    <div className="space-y-1 text-sm text-neutral-500">
      {order.captureId && <p>{`Payment reference (demo): ${order.captureId}`}</p>}
      {order.refundId && <p>{`Refund reference (demo): ${order.refundId}`}</p>}
    </div>
  );
}
