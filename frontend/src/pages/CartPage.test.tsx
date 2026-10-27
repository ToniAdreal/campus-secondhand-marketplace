import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api } from '../api/client';
import { useAuthStore } from '../store/useAuthStore';
import { useCartStore, type CartItem } from '../store/useCartStore';
import CartPage from './CartPage';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');

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
const mug: CartItem = {
  itemId: 10,
  title: 'Coffee mug',
  priceCents: 1500,
  sellerId: 3,
  sellerUsername: 'alice',
};

/** A fresh detail-read DTO for a held cart item. */
function itemDto(itemId: number, overrides: Record<string, unknown> = {}) {
  const held = [book, lamp, mug].find((item) => item.itemId === itemId)!;
  return {
    id: itemId,
    title: held.title,
    description: null,
    priceCents: held.priceCents,
    status: 'AVAILABLE',
    sellerId: 3,
    sellerUsername: 'alice',
    categoryId: null,
    categoryName: null,
    photoUrl: null,
    photos: [],
    expiresAt: null,
    ...overrides,
  };
}

/** Resolves the detail read for every held item from itemDto (AVAILABLE unless overridden; null = 404). */
function mockFreshReads(overridesById: Record<number, Record<string, unknown> | null> = {}) {
  getSpy.mockImplementation((url: string) => {
    const itemId = Number(url.split('/').pop());
    const overrides = overridesById[itemId];
    if (overrides === null) {
      return Promise.reject({ response: { status: 404 } });
    }
    return Promise.resolve(envelope(itemDto(itemId, overrides ?? {})));
  });
}

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function orderFor(itemId: number) {
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

function renderCart() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/cart']}>
        <Routes>
          <Route path="/cart" element={<CartPage />} />
          <Route path="/orders/:id" element={<div>order page</div>} />
          <Route path="/items/:id" element={<div>item page</div>} />
          <Route path="/login" element={<div>login page</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  // Default: the detail read fails with a non-404 error, so an
  // on-open revalidation keeps the stale cart instead of dropping
  // rows or reaching the network. Tests that need fresh reads install
  // their own mock via mockFreshReads.
  getSpy.mockImplementation(() =>
    Promise.reject({ response: { status: 500 } }),
  );
  useAuthStore.setState({
    user: { id: 5, username: 'buyer', email: 'b@example.com', roles: ['USER'] },
  });
  useCartStore.setState({ items: [], sellerId: null, sellerUsername: null, batchId: null });
});

afterEach(() => {
  cleanup();
  useAuthStore.setState({ user: null });
});

describe('CartPage', () => {
  it('shows the empty state when nothing is held', () => {
    renderCart();
    expect(screen.getByText(/Your cart is empty/)).toBeTruthy();
  });

  it('anonymous visitors are bounced to login and no checkout fires', () => {
    useAuthStore.setState({ user: null });
    useCartStore.getState().addItem(book);
    renderCart();
    expect(screen.getByRole('link', { name: 'Log in' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Check out' })).toBeNull();
    expect(postSpy).not.toHaveBeenCalled();
  });

  it('lists the held items with the total and removes an item', () => {
    useCartStore.getState().addItem(book);
    useCartStore.getState().addItem(lamp);
    renderCart();
    expect(screen.getByText('Algorithms textbook')).toBeTruthy();
    expect(screen.getByText('Desk lamp')).toBeTruthy();
    expect(screen.getByText('Total: ¥65.00')).toBeTruthy();
    expect(screen.getByText('Seller: alice')).toBeTruthy();

    fireEvent.click(screen.getAllByRole('button', { name: 'Remove' })[0]);
    expect(screen.queryByText('Algorithms textbook')).toBeNull();
    expect(screen.getByText('Total: ¥20.00')).toBeTruthy();
  });

  it('checkout posts one order per item with the batch-derived keys, then empties the cart', async () => {
    useCartStore.getState().addItem(book);
    useCartStore.getState().addItem(lamp);
    const batchId = useCartStore.getState().batchId!;
    mockFreshReads();
    postSpy.mockImplementation((_url: string, body: unknown) =>
      Promise.resolve(envelope(orderFor((body as { itemId: number }).itemId))),
    );
    renderCart();

    fireEvent.click(screen.getByRole('button', { name: 'Check out' }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(2));
    expect(postSpy.mock.calls.map((call) => call[2]?.headers?.['Idempotency-Key'])).toEqual([
      `${batchId}:7`,
      `${batchId}:8`,
    ]);
    expect(await screen.findByText(/Ordered: Algorithms textbook/)).toBeTruthy();
    expect(screen.getByText(/Ordered: Desk lamp/)).toBeTruthy();
    expect(useCartStore.getState().items).toEqual([]);
  });

  it('partial failure: the raced item stays in the cart, its sibling is ordered and reported', async () => {
    useCartStore.getState().addItem(book);
    useCartStore.getState().addItem(lamp);
    mockFreshReads();
    postSpy.mockImplementation((_url: string, body: unknown) => {
      const itemId = (body as { itemId: number }).itemId;
      if (itemId === 8) {
        return Promise.reject({
          response: { data: { code: 409, message: 'already has an active order' } },
        });
      }
      return Promise.resolve(envelope(orderFor(itemId)));
    });
    renderCart();

    fireEvent.click(screen.getByRole('button', { name: 'Check out' }));

    expect(await screen.findByText(/Ordered: Algorithms textbook/)).toBeTruthy();
    expect(
      screen.getByText(/Could not order Desk lamp: Someone just grabbed this item/),
    ).toBeTruthy();
    // failed item kept for a retry; succeeded item gone
    expect(useCartStore.getState().items.map((i) => i.itemId)).toEqual([8]);
    expect(screen.getByText('Total: ¥20.00')).toBeTruthy();
  });

  it('revalidates on open: a stale price refreshes and the total follows', async () => {
    useCartStore.getState().addItem(book);
    useCartStore.getState().addItem(lamp);
    mockFreshReads({ 7: { priceCents: 5000 } });
    renderCart();

    expect(await screen.findByText('Total: ¥70.00')).toBeTruthy();
    expect(
      screen.getByText("Some prices were updated to the seller's latest prices."),
    ).toBeTruthy();
  });

  it('revalidates on open: a sold item drops with copy naming it', async () => {
    useCartStore.getState().addItem(book);
    useCartStore.getState().addItem(lamp);
    mockFreshReads({ 7: { status: 'SOLD' } });
    renderCart();

    expect(
      await screen.findByText(
        'Algorithms textbook was sold and has been removed from your cart.',
      ),
    ).toBeTruthy();
    expect(screen.getByText('Desk lamp')).toBeTruthy();
    expect(screen.getByText('Total: ¥20.00')).toBeTruthy();
    expect(useCartStore.getState().items.map((i) => i.itemId)).toEqual([8]);
  });

  it('revalidates on open: withdrawn and vanished items drop with their own copy', async () => {
    useCartStore.getState().addItem(book);
    useCartStore.getState().addItem(lamp);
    useCartStore.getState().addItem(mug);
    mockFreshReads({ 7: { status: 'WITHDRAWN' }, 8: null });
    renderCart();

    expect(
      await screen.findByText(
        'Algorithms textbook was withdrawn by the seller and has been removed from your cart.',
      ),
    ).toBeTruthy();
    expect(
      screen.getByText('Desk lamp is no longer available and has been removed from your cart.'),
    ).toBeTruthy();
    expect(screen.getByText('Coffee mug')).toBeTruthy();
    expect(screen.getByText('Total: ¥15.00')).toBeTruthy();
    expect(useCartStore.getState().items.map((i) => i.itemId)).toEqual([10]);
  });

  it('checkout revalidates first and still reports per-item outcomes on the original keys', async () => {
    useCartStore.getState().addItem(book);
    useCartStore.getState().addItem(lamp);
    const batchId = useCartStore.getState().batchId!;
    mockFreshReads();
    postSpy.mockImplementation((_url: string, body: unknown) => {
      const itemId = (body as { itemId: number }).itemId;
      if (itemId === 8) {
        return Promise.reject({
          response: { data: { code: 409, message: 'already has an active order' } },
        });
      }
      return Promise.resolve(envelope(orderFor(itemId)));
    });
    renderCart();

    fireEvent.click(screen.getByRole('button', { name: 'Check out' }));

    expect(await screen.findByText(/Ordered: Algorithms textbook/)).toBeTruthy();
    expect(
      screen.getByText(/Could not order Desk lamp: Someone just grabbed this item/),
    ).toBeTruthy();
    expect(getSpy).toHaveBeenCalledWith('/items/7');
    expect(getSpy).toHaveBeenCalledWith('/items/8');
    expect(postSpy.mock.calls.map((call) => call[2]?.headers?.['Idempotency-Key'])).toEqual([
      `${batchId}:7`,
      `${batchId}:8`,
    ]);
  });

  it('checkout revalidates immediately before ordering: a just-sold item never reaches POST', async () => {
    useCartStore.getState().addItem(book);
    useCartStore.getState().addItem(lamp);
    const batchId = useCartStore.getState().batchId!;
    let bookStatus = 'AVAILABLE';
    getSpy.mockImplementation((url: string) => {
      const itemId = Number(url.split('/').pop());
      return Promise.resolve(
        envelope(itemDto(itemId, itemId === 7 ? { status: bookStatus } : {})),
      );
    });
    postSpy.mockImplementation((_url: string, body: unknown) =>
      Promise.resolve(envelope(orderFor((body as { itemId: number }).itemId))),
    );
    renderCart();

    // Wait out the on-open pass (both reads) before flipping the listing.
    await waitFor(() => expect(getSpy.mock.calls.length).toBeGreaterThanOrEqual(2));
    bookStatus = 'SOLD';
    fireEvent.click(screen.getByRole('button', { name: 'Check out' }));

    expect(
      await screen.findByText(
        'Algorithms textbook was sold and has been removed from your cart.',
      ),
    ).toBeTruthy();
    expect(await screen.findByText(/Ordered: Desk lamp/)).toBeTruthy();
    expect(postSpy).toHaveBeenCalledTimes(1);
    expect(postSpy.mock.calls[0][2]?.headers?.['Idempotency-Key']).toBe(`${batchId}:8`);
    expect(useCartStore.getState().items).toEqual([]);
  });
});
