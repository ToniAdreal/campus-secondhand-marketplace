import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { Item } from '../api/items';
import { useCartStore } from '../store/useCartStore';

/**
 * "Add to cart" for the same-seller bundle cart (backlog #207). The
 * cart holds one seller's listings at a time: adding another seller's
 * listing never silently drops the held cart — the buyer is asked to
 * choose between keeping it and clearing it for the new item.
 */
export default function AddToCartButton({ item }: { item: Item }) {
  const addItem = useCartStore((s) => s.addItem);
  const replaceCartWith = useCartStore((s) => s.replaceCartWith);
  const sellerUsername = useCartStore((s) => s.sellerUsername);
  const [notice, setNotice] = useState<'added' | 'already' | null>(null);
  const [crossSeller, setCrossSeller] = useState(false);

  const asCartItem = {
    itemId: item.id,
    title: item.title,
    priceCents: item.priceCents,
    sellerId: item.sellerId,
    sellerUsername: item.sellerUsername,
  };

  const handleAdd = () => {
    const result = addItem(asCartItem);
    if (result === 'added') {
      setNotice('added');
      setCrossSeller(false);
    } else if (result === 'already-in-cart') {
      setNotice('already');
      setCrossSeller(false);
    } else {
      setNotice(null);
      setCrossSeller(true);
    }
  };

  return (
    <div className="mt-4">
      <button
        type="button"
        onClick={handleAdd}
        className="rounded bg-neutral-800 px-5 py-2 font-medium text-white hover:bg-neutral-900"
      >
        Add to cart
      </button>
      {notice === 'added' && (
        <p className="mt-2 text-sm text-neutral-600">
          Added to your cart.{' '}
          <Link to="/cart" className="text-blue-600 hover:underline">
            View cart
          </Link>
        </p>
      )}
      {notice === 'already' && (
        <p className="mt-2 text-sm text-neutral-600">
          This item is already in your cart.{' '}
          <Link to="/cart" className="text-blue-600 hover:underline">
            View cart
          </Link>
        </p>
      )}
      {crossSeller && (
        <div className="mt-2 text-sm text-neutral-700">
          <p>
            Your cart holds items from {sellerUsername ?? 'another seller'}. A cart can only
            hold one seller&apos;s items at a time.
          </p>
          <div className="mt-2 flex gap-3">
            <button
              type="button"
              onClick={() => {
                replaceCartWith(asCartItem);
                setCrossSeller(false);
                setNotice('added');
              }}
              className="rounded bg-blue-600 px-4 py-1.5 font-medium text-white hover:bg-blue-700"
            >
              Clear cart and add this item
            </button>
            <button
              type="button"
              onClick={() => setCrossSeller(false)}
              className="rounded bg-neutral-200 px-4 py-1.5 font-medium text-neutral-800 hover:bg-neutral-300"
            >
              Keep current cart
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
