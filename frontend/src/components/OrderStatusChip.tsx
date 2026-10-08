import type { Order } from '../api/orders';

/**
 * Shared order status chip (first introduced with the My-orders page, #53).
 * The colors are semantic and stable: pending/paid/completed look
 * optimistic, cancelled looks spent, refunded looks reversed. Both the
 * buyer's order list and the seller's order list render through this so the
 * two pages can never drift apart.
 */

const CHIP_CLASSES: Record<Order['status'], string> = {
  PENDING: 'bg-amber-100 text-amber-800',
  PAID: 'bg-green-100 text-green-800',
  COMPLETED: 'bg-sky-100 text-sky-800',
  CANCELLED: 'bg-neutral-200 text-neutral-600',
  REFUNDED: 'bg-purple-100 text-purple-800',
};

export default function OrderStatusChip({ status }: { status: Order['status'] }) {
  return (
    <span className={`rounded px-2 py-0.5 text-xs font-medium ${CHIP_CLASSES[status]}`}>
      {status}
    </span>
  );
}
