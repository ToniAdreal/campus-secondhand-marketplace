import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useBlockUser, useMyBlocks, useUnblockUser } from '../api/blocks';
import { useFollowSeller, useMyFollows, useUnfollowSeller } from '../api/follows';
import { listingPhotoUrls } from '../api/items';
import {
  describeReviewResponseError,
  useRespondToReview,
  useSellerReviews,
  type SellerReview,
} from '../api/reviews';
import { useSellerListings, useSellerProfile } from '../api/sellers';
import { useAuthStore } from '../store/useAuthStore';
import FavoriteButton from '../components/FavoriteButton';
import ListingPhoto from '../components/ListingPhoto';

/**
 * The seller's response form for one review (backlog #246): a
 * textarea prefilled with the current reply (empty when the seller
 * has not answered yet) and one submit — the first response and
 * every edit are the same idempotent PUT, so there is no separate
 * "edit" mode. The parent keys this form by the server reply, so a
 * successful submit (which invalidates the reviews query) resets
 * the draft to the fresh server text instead of leaving stale input.
 */
function SellerResponseForm({ review }: { review: SellerReview }) {
  const [draft, setDraft] = useState(review.sellerResponse ?? '');
  const respond = useRespondToReview();
  return (
    <form
      className="mt-3"
      onSubmit={(e) => {
        e.preventDefault();
        respond.mutate({ reviewId: review.id, response: draft });
      }}
    >
      <label className="text-xs font-medium text-neutral-600" htmlFor={`response-${review.id}`}>
        {review.sellerResponse ? 'Edit your response' : 'Respond to this review'}
      </label>
      <textarea
        id={`response-${review.id}`}
        aria-label={`Seller response to ${review.reviewerUsername ?? 'this buyer'}'s review`}
        className="mt-1 block w-full rounded border border-neutral-300 px-2 py-1 text-sm"
        rows={3}
        maxLength={1000}
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
      />
      <button
        type="submit"
        disabled={respond.isLoading || draft.trim() === ''}
        className="mt-2 rounded bg-blue-600 px-3 py-1 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
      >
        {review.sellerResponse ? 'Save response' : 'Respond'}
      </button>
      {respond.isError && (
        <p className="mt-1 text-sm text-red-600">
          {describeReviewResponseError(respond.error)}
        </p>
      )}
    </form>
  );
}

/**
 * Public seller profile (backlog #174): the storefront behind a
 * listing's "Sold by" line — who the seller is, how long they have
 * been around, how many sales they have completed, and their live
 * (AVAILABLE) listings in the same card grid as the browse page.
 * Backlog #175 adds the trust signal below the header: the review
 * aggregate the profile carries (average + count, computed server-side
 * from the review rows) and the newest-first review list itself.
 * Backlog #246 adds the seller's reply under each review, plus the
 * response form when the viewer is the seller — this page is both
 * the public profile and the seller's own review list, so one
 * surface covers both renders the spec asks for.
 * Unknown or disabled sellers answer 404 on the backend, which this
 * page renders as a plain "not found" note.
 */
export default function SellerProfilePage() {
  const { id } = useParams();
  const sellerId = Number(id);
  const profile = useSellerProfile(sellerId);
  const listings = useSellerListings(sellerId);
  const reviews = useSellerReviews(sellerId);
  // Blocking (backlog #187): a signed-in visitor who is not the seller
  // can block/unblock them from this public surface. The block state
  // derives from the one blocks query, exactly like favorites.
  const user = useAuthStore((s) => s.user);
  const canBlock = user !== null && user.id !== sellerId;
  const myBlocks = useMyBlocks(canBlock);
  const blockUser = useBlockUser();
  const unblockUser = useUnblockUser();
  const isBlocked = (myBlocks.data ?? []).some((b) => b.blockedId === sellerId);
  // Following (backlog #217): a signed-in visitor who is not the
  // seller can follow/unfollow them from this public surface. The
  // follow state derives from the one follows query, exactly like
  // favorites. The control hides when the visitor blocked the seller:
  // the follow endpoint would answer 409 there, and the Unblock button
  // above already communicates the state — no dead button.
  const canFollow = canBlock && !isBlocked;
  const myFollows = useMyFollows(canFollow);
  const followSeller = useFollowSeller();
  const unfollowSeller = useUnfollowSeller();
  const isFollowed = (myFollows.data ?? []).some((f) => f.sellerId === sellerId);

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
      {canBlock &&
        (isBlocked ? (
          <button
            type="button"
            onClick={() => unblockUser.mutate(sellerId)}
            disabled={unblockUser.isLoading}
            className="mt-3 rounded border border-neutral-300 px-3 py-1 text-sm font-medium text-neutral-700 hover:bg-neutral-100 disabled:opacity-50"
          >
            Unblock user
          </button>
        ) : (
          <button
            type="button"
            onClick={() => blockUser.mutate(sellerId)}
            disabled={blockUser.isLoading}
            className="mt-3 rounded border border-neutral-300 px-3 py-1 text-sm font-medium text-red-700 hover:bg-red-50 disabled:opacity-50"
          >
            Block user
          </button>
        ))}

      {canFollow &&
        (isFollowed ? (
          <button
            type="button"
            aria-label={`Unfollow ${profile.data.username}`}
            onClick={() => unfollowSeller.mutate(sellerId)}
            disabled={unfollowSeller.isLoading}
            className="mt-3 ml-2 rounded border border-neutral-300 px-3 py-1 text-sm font-medium text-neutral-700 hover:bg-neutral-100 disabled:opacity-50"
          >
            Following
          </button>
        ) : (
          <button
            type="button"
            aria-label={`Follow ${profile.data.username}`}
            onClick={() => followSeller.mutate(sellerId)}
            disabled={followSeller.isLoading}
            className="mt-3 ml-2 rounded bg-blue-600 px-3 py-1 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            Follow
          </button>
        ))}

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
              {review.sellerResponse && (
                <div className="mt-2 rounded bg-neutral-50 p-3">
                  <p className="text-xs font-medium text-neutral-600">Seller&apos;s response</p>
                  <p className="mt-1 text-sm">{review.sellerResponse}</p>
                </div>
              )}
              {user !== null && user.id === sellerId && (
                <SellerResponseForm
                  key={`${review.id}:${review.sellerResponse ?? ''}`}
                  review={review}
                />
              )}
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
                  <ListingPhoto
                    src={photoUrls[0]}
                    alt={item.title}
                    imgClassName="mb-3 h-40 w-full rounded object-cover"
                    placeholderClassName="mb-3 flex h-40 items-center justify-center rounded bg-neutral-100 text-sm text-neutral-500"
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
