import type { Order } from '../api/orders';

/**
 * Shared order status chip (first introduced with the My-orders page, #53).
 * The colors are semantic and stable: pending/paid/completed look
 * optimistic, cancelled looks spent, refunded looks reversed. Both the
 * buyer's order list and the seller's order list render through this so the
 * two pages can never drift apart.
 */

// Dark variants (backlog #227) keep the same semantic hues at a darker
// value with lighter text, so chips stay legible on the dark shell —
// the light values are untouched.
const CHIP_CLASSES: Record<Order['status'], string> = {
  PENDING: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200',
  PAID: 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200',
  SHIPPED: 'bg-teal-100 text-teal-800 dark:bg-teal-900 dark:text-teal-200',
  COMPLETED: 'bg-sky-100 text-sky-800 dark:bg-sky-900 dark:text-sky-200',
  CANCELLED: 'bg-neutral-200 text-neutral-600 dark:bg-neutral-700 dark:text-neutral-200',
  REFUNDED: 'bg-purple-100 text-purple-800 dark:bg-purple-900 dark:text-purple-200',
  DISPUTED: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200',
};

export default function OrderStatusChip({ status }: { status: Order['status'] }) {
  return (
    <span className={`rounded px-2 py-0.5 text-xs font-medium ${CHIP_CLASSES[status]}`}>
      {status}
    </span>
  );
}
