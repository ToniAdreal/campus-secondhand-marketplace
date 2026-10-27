import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { ApiResponse, Item } from '../api/items';
import { describeOrderError, useCreateOrderWithKey } from '../api/orders';
import { useAuthStore } from '../store/useAuthStore';
import {
  cartTotalCents,
  checkoutCart,
  revalidateCartItems,
  useCartStore,
  type CartCheckoutOutcome,
  type CartItem,
  type CartRevalidation,
  type CartRevalidationDrop,
  type FreshListing,
} from '../store/useCartStore';

/**
 * Same-seller bundle cart (backlog #207): a local, frontend-only cart
 * (no backend cart resource in v1). Checkout walks the existing
 * POST /api/orders once per item with keys derived from the cart's
 * batch id, so a retry replays instead of double-ordering; a failure
 * on one item never rolls back its siblings — the outcome list says
 * per item what happened, succeeded items leave the cart, failed
 * items stay for a retry.
 *
 * Cart revalidation (backlog #244): the cart shows the title and
 * price captured at add time, so it revalidates against the existing
 * item detail read when the page opens and again immediately before
 * checkout — title/price refresh in place, sold/withdrawn/expired
 * rows drop with copy naming them, and the batch id is preserved
 * while anything is kept, so a revalidated checkout reuses the same
 * idempotency keys and never double-orders.
 */

/**
 * One fresh detail read for a cart item. A 404 maps to null — the
 * detail read 404s deleted, expired and draft-hidden listings alike,
 * so 'unavailable' is the honest reason, never a more specific claim.
 * Any other failure throws: the caller keeps the stale cart rather
 * than dropping rows on a network blip.
 *
 * Side effect, stated plainly: the detail read counts a view for an
 * authenticated non-seller caller (#186), so opening the cart bumps
 * each held listing's view counter — the price of reusing the
 * existing item read instead of adding a dedicated cart endpoint.
 */
async function fetchFreshListing(itemId: number): Promise<FreshListing | null> {
  try {
    const res = await api.get<ApiResponse<Item>>(`/items/${itemId}`);
    return res.data.data;
  } catch (error) {
    if ((error as { response?: { status?: number } }).response?.status === 404) {
      return null;
    }
    throw error;
  }
}

/** Sequential on purpose: one failed read aborts the whole pass, leaving the cart untouched. */
async function revalidateCart(items: CartItem[]): Promise<CartRevalidation> {
  const freshById = new Map<number, FreshListing | null>();
  for (const item of items) {
    freshById.set(item.itemId, await fetchFreshListing(item.itemId));
  }
  return revalidateCartItems(items, freshById);
}

function revalidationDropCopy(drop: CartRevalidationDrop): string {
  switch (drop.reason) {
    case 'sold':
      return `${drop.title} was sold and has been removed from your cart.`;
    case 'withdrawn':
      return `${drop.title} was withdrawn by the seller and has been removed from your cart.`;
    case 'expired':
      return `${drop.title} expired and has been removed from your cart.`;
    case 'unavailable':
      return `${drop.title} is no longer available and has been removed from your cart.`;
  }
}

interface RevalidationNotice {
  dropped: CartRevalidationDrop[];
  pricesChanged: boolean;
  failed: boolean;
}
export default function CartPage() {
  const user = useAuthStore((s) => s.user);
  const items = useCartStore((s) => s.items);
  const sellerUsername = useCartStore((s) => s.sellerUsername);
  const batchId = useCartStore((s) => s.batchId);
  const removeItem = useCartStore((s) => s.removeItem);
  const removeItems = useCartStore((s) => s.removeItems);
  const applyRevalidation = useCartStore((s) => s.applyRevalidation);
  const clear = useCartStore((s) => s.clear);
  const createOrder = useCreateOrderWithKey();
  const [checkingOut, setCheckingOut] = useState(false);
  const [outcomes, setOutcomes] = useState<CartCheckoutOutcome[] | null>(null);
  const [revalidation, setRevalidation] = useState<RevalidationNotice | null>(null);
  const revalidatedOnOpen = useRef(false);

  // Revalidate once when the cart opens. The buyer's own edits win:
  // the result is applied only if the cart still holds exactly the
  // snapshot (a Remove/Clear in the meantime stands, never resurrected).
  useEffect(() => {
    if (!user || revalidatedOnOpen.current) return;
    const snapshot = useCartStore.getState().items;
    if (snapshot.length === 0) return;
    revalidatedOnOpen.current = true;
    revalidateCart(snapshot)
      .then((result) => {
        const current = useCartStore.getState().items;
        if (current.map((i) => i.itemId).join(',') !== snapshot.map((i) => i.itemId).join(',')) {
          return;
        }
        applyRevalidation(result.kept);
        if (result.dropped.length > 0 || result.pricesChanged) {
          setRevalidation({ dropped: result.dropped, pricesChanged: result.pricesChanged, failed: false });
        }
      })
      .catch(() => {
        setRevalidation({ dropped: [], pricesChanged: false, failed: true });
      });
  }, [user, applyRevalidation]);

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
      // Revalidate immediately before committing to checkout. A failed
      // revalidation blocks the checkout (never order on unverified
      // data); a fully-dropped cart orders nothing — the notice names
      // every dropped row. The batch id is preserved while anything is
      // kept, so the keys below are the cart's original keys.
      let checkoutItems = items;
      try {
        const result = await revalidateCart(items);
        if (result.dropped.length > 0 || result.pricesChanged) {
          setRevalidation({
            dropped: result.dropped,
            pricesChanged: result.pricesChanged,
            failed: false,
          });
        }
        applyRevalidation(result.kept);
        checkoutItems = result.kept;
        if (checkoutItems.length === 0) return;
      } catch {
        setRevalidation({ dropped: [], pricesChanged: false, failed: true });
        return;
      }
      const results = await checkoutCart(
        checkoutItems,
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

      {revalidation &&
        (revalidation.failed || revalidation.dropped.length > 0 || revalidation.pricesChanged) && (
          <div className="mt-4">
            {revalidation.failed ? (
              <p role="alert" className="text-sm text-red-600">
                Couldn&apos;t refresh your cart — prices and availability may be out of date.
                Please try again.
              </p>
            ) : (
              <>
                {revalidation.dropped.length > 0 && (
                  <ul className="space-y-1 text-sm text-neutral-700">
                    {revalidation.dropped.map((drop) => (
                      <li key={drop.itemId}>{revalidationDropCopy(drop)}</li>
                    ))}
                  </ul>
                )}
                {revalidation.pricesChanged && (
                  <p className="mt-1 text-sm text-neutral-700">
                    Some prices were updated to the seller&apos;s latest prices.
                  </p>
                )}
              </>
            )}
          </div>
        )}

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
            Prices and availability are refreshed when you open your cart and again before
            checkout. Items that were sold, withdrawn, or expired are removed first; the rest
            still go through, one order per item.
          </p>
        </>
      )}
    </section>
  );
}
