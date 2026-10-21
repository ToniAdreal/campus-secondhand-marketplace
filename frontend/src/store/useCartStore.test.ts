import { beforeEach, describe, expect, it, vi } from 'vitest';
import { describeOrderError, type Order } from '../api/orders';
import {
  cartTotalCents,
  checkoutCart,
  deriveCartIdempotencyKey,
  useCartStore,
  type CartItem,
} from './useCartStore';

const book: CartItem = {
  itemId: 7,
  title: 'Algorithms textbook',
  priceCents: 4500,
  sellerId: 3,
  sellerUsername: 'alice',
};
const lamp: CartItem = {
  itemId: 8,
  title: 'Desk lamp',
  priceCents: 2000,
  sellerId: 3,
  sellerUsername: 'alice',
};
const chairOtherSeller: CartItem = {
  itemId: 9,
  title: 'Chair',
  priceCents: 8000,
  sellerId: 4,
  sellerUsername: 'bob',
};

function reset() {
  useCartStore.setState({ items: [], sellerId: null, sellerUsername: null, batchId: null });
}

beforeEach(reset);

describe('useCartStore add', () => {
  it('adds a first item and binds the cart to its seller with a fresh batch id', () => {
    expect(useCartStore.getState().addItem(book)).toBe('added');
    const state = useCartStore.getState();
    expect(state.items).toEqual([book]);
    expect(state.sellerId).toBe(3);
    expect(state.batchId).toBeTruthy();
  });

  it('adds a second item from the same seller and totals the prices', () => {
    useCartStore.getState().addItem(book);
    expect(useCartStore.getState().addItem(lamp)).toBe('added');
    expect(cartTotalCents(useCartStore.getState().items)).toBe(6500);
  });

  it('reports a duplicate add without duplicating the row', () => {
    useCartStore.getState().addItem(book);
    expect(useCartStore.getState().addItem(book)).toBe('already-in-cart');
    expect(useCartStore.getState().items).toHaveLength(1);
  });

  it("reports another seller's item without touching the held cart — the UI must ask, never silently drop", () => {
    useCartStore.getState().addItem(book);
    const batchBefore = useCartStore.getState().batchId;
    expect(useCartStore.getState().addItem(chairOtherSeller)).toBe('different-seller');
    const state = useCartStore.getState();
    expect(state.items).toEqual([book]);
    expect(state.sellerId).toBe(3);
    expect(state.batchId).toBe(batchBefore);
  });

  it('replaceCartWith clears the old cart and starts a new batch for the new seller', () => {
    useCartStore.getState().addItem(book);
    const batchBefore = useCartStore.getState().batchId;
    useCartStore.getState().replaceCartWith(chairOtherSeller);
    const state = useCartStore.getState();
    expect(state.items).toEqual([chairOtherSeller]);
    expect(state.sellerId).toBe(4);
    expect(state.batchId).toBeTruthy();
    expect(state.batchId).not.toBe(batchBefore);
  });

  it('removing the last item unbinds the seller and the batch', () => {
    useCartStore.getState().addItem(book);
    useCartStore.getState().removeItem(7);
    const state = useCartStore.getState();
    expect(state.items).toEqual([]);
    expect(state.sellerId).toBeNull();
    expect(state.batchId).toBeNull();
  });
});

describe('deriveCartIdempotencyKey', () => {
  it('is deterministic in batch id + item id — a retry derives the identical key', () => {
    expect(deriveCartIdempotencyKey('batch-1', 7)).toBe('batch-1:7');
    expect(deriveCartIdempotencyKey('batch-1', 7)).toBe(deriveCartIdempotencyKey('batch-1', 7));
    expect(deriveCartIdempotencyKey('batch-1', 8)).not.toBe(deriveCartIdempotencyKey('batch-1', 7));
    expect(deriveCartIdempotencyKey('batch-2', 7)).not.toBe(deriveCartIdempotencyKey('batch-1', 7));
  });
});

function fakeOrder(itemId: number): Order {
  return {
    id: 100 + itemId,
    itemId,
    buyerId: 5,
    status: 'PENDING',
    amountCents: 1,
    createdAt: '2026-10-21T00:00:00Z',
    captureId: null,
    refundId: null,
  };
}

describe('checkoutCart', () => {
  it('creates one order per item, each with its derived key', async () => {
    const createOrder = vi.fn(async (itemId: number) => fakeOrder(itemId));
    const outcomes = await checkoutCart([book, lamp], 'batch-9', createOrder);
    expect(createOrder.mock.calls).toEqual([
      [7, 'batch-9:7'],
      [8, 'batch-9:8'],
    ]);
    expect(outcomes).toEqual([
      { itemId: 7, title: book.title, ok: true, orderId: 107 },
      { itemId: 8, title: lamp.title, ok: true, orderId: 108 },
    ]);
  });

  it('a 409 on one item does not roll back its siblings — outcomes report per item', async () => {
    const conflict = { response: { data: { code: 409, message: 'already has an active order' } } };
    const createOrder = vi.fn(async (itemId: number) => {
      if (itemId === 8) throw conflict;
      return fakeOrder(itemId);
    });
    const outcomes = await checkoutCart([book, lamp], 'batch-9', createOrder, describeOrderError);
    expect(outcomes[0]).toMatchObject({ itemId: 7, ok: true, orderId: 107 });
    expect(outcomes[1]).toEqual({
      itemId: 8,
      title: lamp.title,
      ok: false,
      errorMessage: 'Someone just grabbed this item — it is no longer available.',
    });
  });

  it('a retried checkout reuses the same keys — the server replays instead of double-ordering', async () => {
    const createOrder = vi.fn(async (itemId: number, _key: string) => fakeOrder(itemId));
    await checkoutCart([book, lamp], 'batch-9', createOrder);
    const firstKeys = createOrder.mock.calls.map((call) => call[1]);
    createOrder.mockClear();
    await checkoutCart([book, lamp], 'batch-9', createOrder);
    expect(createOrder.mock.calls.map((call) => call[1])).toEqual(firstKeys);
  });
});
