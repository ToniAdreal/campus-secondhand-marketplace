import { Link, useParams } from 'react-router-dom';
import { listingPhotoUrls } from '../api/items';
import { useSellerListings, useSellerProfile } from '../api/sellers';
import FavoriteButton from '../components/FavoriteButton';

/**
 * Public seller profile (backlog #174): the storefront behind a
 * listing's "Sold by" line — who the seller is, how long they have
 * been around, how many sales they have completed, and their live
 * (AVAILABLE) listings in the same card grid as the browse page.
 * Unknown or disabled sellers answer 404 on the backend, which this
 * page renders as a plain "not found" note.
 */
export default function SellerProfilePage() {
  const { id } = useParams();
  const sellerId = Number(id);
  const profile = useSellerProfile(sellerId);
  const listings = useSellerListings(sellerId);

  if (profile.isLoading) return <p>Loading seller…</p>;
  if (profile.isError || !profile.data) {
    return <p className="text-red-600">Seller not found.</p>;
  }

  const memberSince = new Date(profile.data.memberSince).toLocaleDateString();

  return (
    <div>
      <h1 className="text-2xl font-bold">{profile.data.username}</h1>
      <p className="mt-2 text-sm text-neutral-600">
        Member since {memberSince} · {profile.data.completedSalesCount} completed sales ·{' '}
        {profile.data.availableListingCount} live listings
      </p>

      <h2 className="mt-6 text-lg font-semibold">Live listings</h2>
      {listings.isLoading && <p>Loading listings…</p>}
      {listings.isError && <p className="text-red-600">Failed to load listings.</p>}
      {listings.data && listings.data.content.length === 0 && (
        <p className="mt-2 text-sm text-neutral-600">No live listings right now.</p>
      )}
      {listings.data && listings.data.content.length > 0 && (
        <ul className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {listings.data.content.map((item) => {
            const photoUrls = listingPhotoUrls(item);
            return (
              <li key={item.id} className="rounded-lg bg-white p-4 shadow-sm">
                {photoUrls.length > 0 ? (
                  <img
                    src={photoUrls[0]}
                    alt={item.title}
                    className="mb-3 h-40 w-full rounded object-cover"
                  />
                ) : (
                  <div
                    data-testid="no-photo-placeholder"
                    className="mb-3 flex h-40 items-center justify-center rounded bg-neutral-100 text-sm text-neutral-500"
                  >
                    No photo
                  </div>
                )}
                <Link to={`/items/${item.id}`} className="font-semibold hover:underline">
                  {item.title}
                </Link>
                <p className="mt-1 text-sm text-neutral-500">
                  ¥{(item.priceCents / 100).toFixed(2)}
                </p>
                <span className="mt-2 inline-block rounded bg-neutral-200 px-2 py-0.5 text-xs">
                  {item.status}
                </span>{' '}
                <FavoriteButton itemId={item.id} />
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
