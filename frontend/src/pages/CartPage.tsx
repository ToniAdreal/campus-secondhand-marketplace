import { useState } from 'react';
import { Link } from 'react-router-dom';
import { describeOrderError, useCreateOrderWithKey } from '../api/orders';
import { useAuthStore } from '../store/useAuthStore';
import {
  cartTotalCents,
  checkoutCart,
  useCartStore,
  type CartCheckoutOutcome,
} from '../store/useCartStore';

/**
 * Same-seller bundle cart (backlog #207): a local, frontend-only cart
 * (no backend cart resource in v1). Checkout walks the existing
 * POST /api/orders once per item with keys derived from the cart's
 * batch id, so a retry replays instead of double-ordering; a failure
 * on one item never rolls back its siblings — the outcome list says
 * per item what happened, succeeded items leave the cart, failed
 * items stay for a retry.
 */
export default function CartPage() {
  const user = useAuthStore((s) => s.user);
  const items = useCartStore((s) => s.items);
  const sellerUsername = useCartStore((s) => s.sellerUsername);
  const batchId = useCartStore((s) => s.batchId);
  const removeItem = useCartStore((s) => s.removeItem);
  const removeItems = useCartStore((s) => s.removeItems);
  const clear = useCartStore((s) => s.clear);
  const createOrder = useCreateOrderWithKey();
  const [checkingOut, setCheckingOut] = useState(false);
  const [outcomes, setOutcomes] = useState<CartCheckoutOutcome[] | null>(null);

  if (!user) {
    return (
      <section className="rounded-lg bg-white p-6 shadow-sm">
        <h1 className="text-2xl font-bold">Your cart</h1>
        <p className="mt-4 text-sm text-neutral-600">
          <Link to="/login" className="text-blue-600 hover:underline">
            Log in
          </Link>{' '}
          to check out your cart.
        </p>
      </section>
    );
  }

  const handleCheckout = async () => {
    if (!batchId || items.length === 0) return;
    setCheckingOut(true);
    setOutcomes(null);
    try {
      const results = await checkoutCart(
        items,
        batchId,
        (itemId, idempotencyKey) => createOrder.mutateAsync({ itemId, idempotencyKey }),
        describeOrderError,
      );
      setOutcomes(results);
      removeItems(results.filter((r) => r.ok).map((r) => r.itemId));
    } finally {
      setCheckingOut(false);
    }
  };

  return (
    <section className="rounded-lg bg-white p-6 shadow-sm">
      <h1 className="text-2xl font-bold">Your cart</h1>

      {outcomes && outcomes.length > 0 && (
        <div className="mt-4">
          <h2 className="text-sm font-semibold">Checkout results</h2>
          <ul className="mt-2 space-y-1 text-sm">
            {outcomes.map((outcome) => (
              <li key={outcome.itemId}>
                {outcome.ok ? (
                  <span className="text-neutral-700">
                    Ordered: {outcome.title} —{' '}
                    <Link
                      to={`/orders/${outcome.orderId}`}
                      className="text-blue-600 hover:underline"
                    >
                      order #{outcome.orderId}
                    </Link>
                  </span>
                ) : (
                  <span role="alert" className="text-red-600">
                    Could not order {outcome.title}: {outcome.errorMessage}
                  </span>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      {items.length === 0 ? (
        <p className="mt-4 text-sm text-neutral-600">
          Your cart is empty.{' '}
          <Link to="/" className="text-blue-600 hover:underline">
            Browse listings
          </Link>
        </p>
      ) : (
        <>
          {sellerUsername && (
            <p className="mt-2 text-sm text-neutral-600">Seller: {sellerUsername}</p>
          )}
          <ul className="mt-4 divide-y">
            {items.map((item) => (
              <li key={item.itemId} className="flex items-center justify-between py-3">
                <Link to={`/items/${item.itemId}`} className="text-blue-600 hover:underline">
                  {item.title}
                </Link>
                <span className="flex items-center gap-3">
                  <span>¥{(item.priceCents / 100).toFixed(2)}</span>
                  <button
                    type="button"
                    onClick={() => removeItem(item.itemId)}
                    className="text-sm text-neutral-500 underline hover:text-neutral-700"
                  >
                    Remove
                  </button>
                </span>
              </li>
            ))}
          </ul>
          <p className="mt-4 font-medium">
            Total: ¥{(cartTotalCents(items) / 100).toFixed(2)}
          </p>
          <div className="mt-4 flex gap-3">
            <button
              type="button"
              onClick={handleCheckout}
              disabled={checkingOut}
              className="rounded bg-blue-600 px-5 py-2 font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {checkingOut ? 'Checking out…' : 'Check out'}
            </button>
            <button
              type="button"
              onClick={clear}
              disabled={checkingOut}
              className="rounded bg-neutral-200 px-5 py-2 font-medium text-neutral-800 hover:bg-neutral-300 disabled:opacity-50"
            >
              Clear cart
            </button>
          </div>
          <p className="mt-2 text-sm text-neutral-500">
            Checkout places one order per item with this seller. If an item was just sold, it
            stays in your cart and the rest still go through.
          </p>
        </>
      )}
    </section>
  );
}
