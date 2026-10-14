import { Link, Navigate, useLocation } from 'react-router-dom';
import { useFavorites } from '../api/favorites';
import { listingPhotoUrls } from '../api/items';
import {
  describeSavedSearch,
  savedSearchBrowsePath,
  useDeleteSavedSearch,
  useSavedSearches,
} from '../api/savedSearches';
import FavoriteButton from '../components/FavoriteButton';
import { useAuthStore } from '../store/useAuthStore';

/**
 * Saved listings (backlog #143) — GET /api/users/me/favorites rendered
 * with the same card shape as the browse grid (photo, title link,
 * price, status badge), newest save first. A saved listing keeps its
 * real status badge: RESERVED/SOLD saves stay listed honestly instead
 * of pretending to still be buyable. The heart on each card unsaves
 * in place (the toggle invalidates the list query). Anonymous
 * visitors bounce to /login like the other buyer pages.
 */
export default function FavoritesPage() {
  const location = useLocation();
  const user = useAuthStore((s) => s.user);
  const favorites = useFavorites(user !== null);
  const savedSearches = useSavedSearches(user !== null);
  const deleteSavedSearch = useDeleteSavedSearch();

  if (!user) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">Favorites</h1>
      {favorites.isLoading && <p>Loading favorites…</p>}
      {favorites.isError && (
        <p role="alert" className="text-red-600">
          Failed to load favorites.
        </p>
      )}
      {favorites.isSuccess && favorites.data.length === 0 && (
        <div className="rounded-lg bg-white p-6 text-center shadow-sm">
          <p className="text-neutral-600">You haven&apos;t saved any listings yet.</p>
          <Link to="/" className="mt-2 inline-block text-sm text-blue-600 hover:underline">
            Browse listings
          </Link>
        </div>
      )}
      {favorites.data && favorites.data.length > 0 && (
        <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {favorites.data.map((item) => {
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
      <h2 className="mb-4 mt-10 text-xl font-bold">Saved searches</h2>
      {savedSearches.isLoading && <p>Loading saved searches…</p>}
      {savedSearches.isError && (
        <p role="alert" className="text-red-600">
          Failed to load saved searches.
        </p>
      )}
      {savedSearches.isSuccess && savedSearches.data.length === 0 && (
        <div className="rounded-lg bg-white p-6 text-center shadow-sm">
          <p className="text-neutral-600">You haven&apos;t saved any searches yet.</p>
          <p className="mt-1 text-sm text-neutral-500">
            Filter the browse page, then use “Save this search” to keep the filter set here.
          </p>
        </div>
      )}
      {savedSearches.data && savedSearches.data.length > 0 && (
        <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {savedSearches.data.map((search) => (
            <li key={search.id} className="rounded-lg bg-white p-4 shadow-sm">
              <Link
                to={savedSearchBrowsePath(search)}
                className="font-semibold hover:underline"
              >
                {describeSavedSearch(search)}
              </Link>
              <p className="mt-1 text-sm text-neutral-500">
                {search.matchCount} matching {search.matchCount === 1 ? 'listing' : 'listings'}
              </p>
              <button
                type="button"
                aria-label={`Delete saved search ${describeSavedSearch(search)}`}
                disabled={deleteSavedSearch.isLoading}
                onClick={() => deleteSavedSearch.mutate(search.id)}
                className="mt-2 inline-block rounded bg-neutral-100 px-2 py-0.5 text-sm hover:bg-neutral-200 disabled:opacity-50"
              >
                Delete
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
