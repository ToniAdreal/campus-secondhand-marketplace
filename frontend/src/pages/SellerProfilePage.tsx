import { Link, useParams } from 'react-router-dom';
import { listingPhotoUrls } from '../api/items';
import { useSellerReviews } from '../api/reviews';
import { useSellerListings, useSellerProfile } from '../api/sellers';
import FavoriteButton from '../components/FavoriteButton';

/**
 * Public seller profile (backlog #174): the storefront behind a
 * listing's "Sold by" line — who the seller is, how long they have
 * been around, how many sales they have completed, and their live
 * (AVAILABLE) listings in the same card grid as the browse page.
 * Backlog #175 adds the trust signal below the header: the review
 * aggregate the profile carries (average + count, computed server-side
 * from the review rows) and the newest-first review list itself.
 * Unknown or disabled sellers answer 404 on the backend, which this
 * page renders as a plain "not found" note.
 */
export default function SellerProfilePage() {
  const { id } = useParams();
  const sellerId = Number(id);
  const profile = useSellerProfile(sellerId);
  const listings = useSellerListings(sellerId);
  const reviews = useSellerReviews(sellerId);

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
      <p className="mt-1 text-sm text-neutral-600">
        {profile.data.averageRating !== null
          ? `★ ${profile.data.averageRating.toFixed(1)} average · ${profile.data.reviewCount} review${profile.data.reviewCount === 1 ? '' : 's'}`
          : 'No reviews yet'}
      </p>

      <h2 className="mt-6 text-lg font-semibold">Reviews</h2>
      {reviews.isLoading && <p>Loading reviews…</p>}
      {reviews.isError && <p className="text-red-600">Failed to load reviews.</p>}
      {reviews.data && reviews.data.content.length === 0 && (
        <p className="mt-2 text-sm text-neutral-600">No reviews yet.</p>
      )}
      {reviews.data && reviews.data.content.length > 0 && (
        <ul className="mt-3 space-y-3">
          {reviews.data.content.map((review) => (
            <li key={review.id} className="rounded-lg bg-white p-4 shadow-sm">
              <p className="text-sm font-medium">
                {'★'.repeat(review.rating)}
                <span className="text-neutral-300">{'★'.repeat(5 - review.rating)}</span>
                {' · '}
                {review.reviewerUsername ?? 'A buyer'}
              </p>
              {review.body && <p className="mt-1 text-sm">{review.body}</p>}
              <p className="mt-1 text-xs text-neutral-500">
                {new Date(review.createdAt).toLocaleDateString()}
              </p>
            </li>
          ))}
        </ul>
      )}

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
