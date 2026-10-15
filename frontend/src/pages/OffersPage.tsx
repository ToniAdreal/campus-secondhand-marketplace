import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  describeOfferError,
  useAcceptOffer,
  useDeclineOffer,
  useMadeOffers,
  useReceivedOffers,
  useWithdrawOffer,
  type PriceOffer,
} from '../api/offers';
import { useAuthStore } from '../store/useAuthStore';

function yuan(cents: number): string {
  return `¥${(cents / 100).toFixed(2)}`;
}

function OfferRow({
  offer,
  side,
}: {
  offer: PriceOffer;
  side: 'made' | 'received';
}) {
  const accept = useAcceptOffer();
  const decline = useDeclineOffer();
  const withdraw = useWithdrawOffer();
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const pending = offer.status === 'PENDING';

  const run = (fn: () => void) => {
    setErrorMessage(null);
    fn();
  };

  return (
    <li className="rounded-lg bg-white p-4 shadow-sm">
      <p className="text-sm">
        <Link to={`/items/${offer.itemId}`} className="text-blue-600 hover:underline">
          Listing #{offer.itemId}
        </Link>{' '}
        <span>— offered {yuan(offer.priceCents)}</span>{' '}
        <span className="ml-1 inline-block rounded bg-neutral-200 px-2 py-0.5 text-xs">
          {offer.status}
        </span>
      </p>
      {offer.status === 'ACCEPTED' && offer.orderId !== null && (
        <p className="mt-1 text-sm text-neutral-600">
          Order #{offer.orderId} was created at the agreed price.
        </p>
      )}
      {pending && side === 'received' && (
        <div className="mt-2 flex gap-3">
          <button
            type="button"
            disabled={accept.isLoading}
            onClick={() =>
              run(() =>
                accept.mutate(offer.id, {
                  onError: (error) => setErrorMessage(describeOfferError(error)),
                }),
              )
            }
            className="rounded bg-blue-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            Accept
          </button>
          <button
            type="button"
            disabled={decline.isLoading}
            onClick={() =>
              run(() =>
                decline.mutate(offer.id, {
                  onError: (error) => setErrorMessage(describeOfferError(error)),
                }),
              )
            }
            className="rounded bg-neutral-200 px-4 py-1.5 text-sm font-medium text-neutral-800 hover:bg-neutral-300 disabled:opacity-50"
          >
            Decline
          </button>
        </div>
      )}
      {pending && side === 'made' && (
        <div className="mt-2">
          <button
            type="button"
            disabled={withdraw.isLoading}
            onClick={() =>
              run(() =>
                withdraw.mutate(offer.id, {
                  onError: (error) => setErrorMessage(describeOfferError(error)),
                }),
              )
            }
            className="rounded bg-neutral-200 px-4 py-1.5 text-sm font-medium text-neutral-800 hover:bg-neutral-300 disabled:opacity-50"
          >
            Withdraw
          </button>
        </div>
      )}
      {errorMessage && (
        <p role="alert" className="mt-2 text-sm text-red-600">
          {errorMessage}
        </p>
      )}
    </li>
  );
}

/**
 * Offer inbox (backlog #168): two sections over the same rows — the
 * offers the caller made as a buyer (withdraw while PENDING) and the
 * offers on the caller's own listings (accept creates the order at the
 * agreed price; decline is final). Anonymous visitors bounce to login
 * and no request fires, mirroring the other account pages.
 */
export default function OffersPage() {
  const user = useAuthStore((s) => s.user);
  const navigate = useNavigate();
  const signedIn = user !== null;
  const made = useMadeOffers(signedIn);
  const received = useReceivedOffers(signedIn);

  useEffect(() => {
    if (!signedIn) {
      navigate('/login');
    }
  }, [signedIn, navigate]);

  if (!signedIn) return null;

  return (
    <section>
      <h1 className="text-2xl font-bold">Offers</h1>

      <h2 className="mt-6 text-lg font-semibold">Offers I made</h2>
      {made.isLoading && <p className="mt-2 text-sm text-neutral-500">Loading…</p>}
      {made.data && made.data.content.length === 0 && (
        <p className="mt-2 text-sm text-neutral-600">
          You have not made any offers yet — use “Make an offer” on a listing.
        </p>
      )}
      <ul className="mt-3 space-y-3">
        {made.data?.content.map((offer) => (
          <OfferRow key={offer.id} offer={offer} side="made" />
        ))}
      </ul>

      <h2 className="mt-8 text-lg font-semibold">Offers on my listings</h2>
      {received.isLoading && <p className="mt-2 text-sm text-neutral-500">Loading…</p>}
      {received.data && received.data.content.length === 0 && (
        <p className="mt-2 text-sm text-neutral-600">
          No offers on your listings yet.
        </p>
      )}
      <ul className="mt-3 space-y-3">
        {received.data?.content.map((offer) => (
          <OfferRow key={offer.id} offer={offer} side="received" />
        ))}
      </ul>
    </section>
  );
}
