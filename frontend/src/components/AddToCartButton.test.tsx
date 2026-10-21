import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { Item } from '../api/items';
import { useCartStore } from '../store/useCartStore';
import AddToCartButton from './AddToCartButton';

function makeItem(overrides: Partial<Item>): Item {
  return {
    id: 7,
    title: 'Algorithms textbook',
    description: null,
    priceCents: 4500,
    status: 'AVAILABLE',
    sellerId: 3,
    sellerUsername: 'alice',
    categoryId: null,
    categoryName: null,
    photoUrl: null,
    photos: [],
    ...overrides,
  };
}

function renderButton(item: Item) {
  return render(
    <MemoryRouter>
      <AddToCartButton item={item} />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  useCartStore.setState({ items: [], sellerId: null, sellerUsername: null, batchId: null });
});

afterEach(cleanup);

describe('AddToCartButton', () => {
  it('adds the item and confirms with a cart link', () => {
    renderButton(makeItem({}));
    fireEvent.click(screen.getByRole('button', { name: 'Add to cart' }));
    expect(screen.getByText(/Added to your cart/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'View cart' })).toBeTruthy();
    expect(useCartStore.getState().items.map((i) => i.itemId)).toEqual([7]);
  });

  it("adding another seller's item asks to clear — nothing is dropped silently", () => {
    useCartStore.getState().addItem({
      itemId: 1,
      title: 'Held item',
      priceCents: 100,
      sellerId: 3,
      sellerUsername: 'alice',
    });
    renderButton(makeItem({ id: 9, sellerId: 4, sellerUsername: 'bob', title: 'Chair' }));

    fireEvent.click(screen.getByRole('button', { name: 'Add to cart' }));

    expect(screen.getByText(/one seller's items at a time/)).toBeTruthy();
    // the held cart is untouched until the buyer chooses
    expect(useCartStore.getState().items.map((i) => i.itemId)).toEqual([1]);

    fireEvent.click(screen.getByRole('button', { name: 'Keep current cart' }));
    expect(useCartStore.getState().items.map((i) => i.itemId)).toEqual([1]);
  });

  it('clear-and-add replaces the cart with the new seller item', () => {
    useCartStore.getState().addItem({
      itemId: 1,
      title: 'Held item',
      priceCents: 100,
      sellerId: 3,
      sellerUsername: 'alice',
    });
    renderButton(makeItem({ id: 9, sellerId: 4, sellerUsername: 'bob', title: 'Chair' }));

    fireEvent.click(screen.getByRole('button', { name: 'Add to cart' }));
    fireEvent.click(screen.getByRole('button', { name: 'Clear cart and add this item' }));

    expect(useCartStore.getState().items.map((i) => i.itemId)).toEqual([9]);
    expect(useCartStore.getState().sellerId).toBe(4);
  });
});
