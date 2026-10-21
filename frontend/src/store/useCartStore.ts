import { create } from 'zustand';
import type { Order } from '../api/orders';

/**
 * Same-seller bundle cart (backlog #207). Frontend-only v1 by design:
 * there is no cart resource on the backend — the cart is local state
 * and checkout walks the existing POST /api/orders once per item.
 *
 * The v1 seller rule: the cart holds listings from ONE seller at a
 * time. Adding another seller's listing does NOT silently drop the
 * current cart and does NOT mix sellers — `addItem` reports
 * 'different-seller' and the UI asks the buyer to choose (clear the
 * cart and start a new one, or keep the current cart).
 */
export interface CartItem {
  itemId: number;
  title: string;
  priceCents: number;
  sellerId: number;
  sellerUsername: string | null;
}

export type AddToCartResult = 'added' | 'already-in-cart' | 'different-seller';

export interface CartCheckoutOutcome {
  itemId: number;
  title: string;
  ok: boolean;
  /** Present on success — the created order's id. */
  orderId?: number;
  /** Present on failure — the friendly copy from describeOrderError. */
  errorMessage?: string;
}

/**
 * The checkout idempotency key for one cart item, derived
 * deterministically from the cart's batch id + the item id. A retried
 * checkout of the same cart therefore reuses the exact same keys, so
 * the backend's Idempotency-Key replay (#9) returns the original
 * orders instead of double-ordering; a new cart (new batch id) is a
 * new logical checkout and gets fresh keys.
 */
export function deriveCartIdempotencyKey(batchId: string, itemId: number): string {
  return `${batchId}:${itemId}`;
}

function newBatchId(): string {
  return crypto.randomUUID();
}

interface CartState {
  items: CartItem[];
  /** The one seller this cart is bound to; null while the cart is empty. */
  sellerId: number | null;
  sellerUsername: string | null;
  /**
   * One client-generated id per cart lifetime, minted when the first
   * item is added and cleared when the cart empties — the seed every
   * checkout key derives from (see deriveCartIdempotencyKey).
   */
  batchId: string | null;
  addItem: (item: CartItem) => AddToCartResult;
  /** Clears the cart, then adds the item as a fresh one-seller cart. */
  replaceCartWith: (item: CartItem) => void;
  removeItem: (itemId: number) => void;
  removeItems: (itemIds: number[]) => void;
  clear: () => void;
}

const emptyCart = { items: [] as CartItem[], sellerId: null, sellerUsername: null, batchId: null };

export const useCartStore = create<CartState>((set, get) => ({
  ...emptyCart,
  addItem: (item) => {
    const { items, sellerId } = get();
    if (items.some((held) => held.itemId === item.itemId)) {
      return 'already-in-cart';
    }
    if (sellerId !== null && sellerId !== item.sellerId) {
      return 'different-seller';
    }
    set({
      items: [...items, item],
      sellerId: item.sellerId,
      sellerUsername: item.sellerUsername,
      batchId: get().batchId ?? newBatchId(),
    });
    return 'added';
  },
  replaceCartWith: (item) => {
    set({
      items: [item],
      sellerId: item.sellerId,
      sellerUsername: item.sellerUsername,
      batchId: newBatchId(),
    });
  },
  removeItem: (itemId) => {
    const remaining = get().items.filter((held) => held.itemId !== itemId);
    set(remaining.length === 0 ? { ...emptyCart } : { items: remaining });
  },
  removeItems: (itemIds) => {
    const remaining = get().items.filter((held) => !itemIds.includes(held.itemId));
    set(remaining.length === 0 ? { ...emptyCart } : { items: remaining });
  },
  clear: () => set({ ...emptyCart }),
}));

/** The cart's total in cents — a plain sum of the held listings' prices. */
export function cartTotalCents(items: CartItem[]): number {
  return items.reduce((sum, item) => sum + item.priceCents, 0);
}

/**
 * Walks the cart once, creating one order per item through the given
 * create function (which must send the derived Idempotency-Key).
 * Sequential on purpose: each outcome is known before the next call,
 * and a failure — e.g. a 409 because the listing was raced while it
 * sat in the cart — never rolls back its siblings. The returned
 * outcome list reports every item honestly; the caller keeps the
 * failed items in the cart and drops the succeeded ones.
 */
export async function checkoutCart(
  items: CartItem[],
  batchId: string,
  createOrder: (itemId: number, idempotencyKey: string) => Promise<Order>,
  // The friendly-copy mapper is injected by the caller (the cart page
  // passes describeOrderError from api/orders) so this store module —
  // imported by the eager App shell for the nav badge — never pulls
  // the orders API module into the entry chunk.
  describeError: (error: unknown) => string = (error) =>
    (error as { response?: { data?: { message?: string } } }).response?.data?.message ||
    'Something went wrong. Please try again.',
): Promise<CartCheckoutOutcome[]> {
  const outcomes: CartCheckoutOutcome[] = [];
  for (const item of items) {
    try {
      const order = await createOrder(item.itemId, deriveCartIdempotencyKey(batchId, item.itemId));
      outcomes.push({ itemId: item.itemId, title: item.title, ok: true, orderId: order.id });
    } catch (error) {
      outcomes.push({
        itemId: item.itemId,
        title: item.title,
        ok: false,
        errorMessage: describeError(error),
      });
    }
  }
  return outcomes;
}
