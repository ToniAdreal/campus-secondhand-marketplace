import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { listingPhotoUrls, useItems, useItemSuggestions } from '../api/items';
import { useRecentlyViewed } from '../api/recentlyViewed';
import FavoriteButton from '../components/FavoriteButton';
import ListingPhoto from '../components/ListingPhoto';
import SaveSearchButton from '../components/SaveSearchButton';
import SearchSuggestions from '../components/SearchSuggestions';
import { useCategories } from '../api/categories';
import { useDebouncedSearchParam } from '../hooks/useDebouncedSearchParam';
import { useAuthStore } from '../store/useAuthStore';

/**
 * Parses the `categoryId` URL parameter. Anything that is not a positive
 * integer (hand-edited links, stale ids) is treated as "no filter" rather
 * than sent to the backend, where it would 400 on @Positive validation.
 */
/**
 * Parses a committed price URL parameter (yuan, as typed) into cents for
 * the backend. Blank, non-numeric, or negative values mean "no bound"
 * rather than a 400 from the backend's @PositiveOrZero check.
 */
export function parsePriceYuanToCents(raw: string): number | null {
  if (raw.trim() === '') {
    return null;
  }
  const yuan = Number(raw);
  return Number.isFinite(yuan) && yuan >= 0 ? Math.round(yuan * 100) : null;
}

/** Allowlisted sort values; anything else falls back to newest-first. */
export function parseSortParam(raw: string | null): 'newest' | 'price-asc' | 'price-desc' {
  return raw === 'price-asc' || raw === 'price-desc' ? raw : 'newest';
}

export function parseCategoryId(raw: string | null): number | null {
  if (raw === null) {
    return null;
  }
  const parsed = Number(raw);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

/**
 * Parses the `page` URL parameter (backlog #144). Pages are 0-based,
 * matching the backend's `page` param and {@link useItems}; the controls
 * render the human-facing "Page x of y" as page + 1. Anything that is
 * not a non-negative integer (hand-edited links, negatives, blanks) is
 * treated as page 0 rather than sent to the backend, where a negative
 * page would 400.
 */
export function parsePageParam(raw: string | null): number {
  if (raw === null || raw.trim() === '') {
    return 0;
  }
  const parsed = Number(raw);
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : 0;
}

export default function HomePage() {
  // Every filter commit drops ?page= (backlog #144): a new result set
  // restarts at page 0 instead of stranding the reader mid-list.
  const [searchInput, searchQuery, setSearchInput] = useDebouncedSearchParam('q', 300, {
    resetParams: ['page'],
  });
  // Price bounds ride the same debounced URL pattern, in yuan as typed
  // (?minPrice=/?maxPrice=); they are converted to cents for the API.
  const [minPriceInput, minPriceCommitted, setMinPriceInput] =
    useDebouncedSearchParam('minPrice', 300, { resetParams: ['page'] });
  const [maxPriceInput, maxPriceCommitted, setMaxPriceInput] =
    useDebouncedSearchParam('maxPrice', 300, { resetParams: ['page'] });
  const [searchParams, setSearchParams] = useSearchParams();
  const categoryId = parseCategoryId(searchParams.get('categoryId'));
  const minPriceCents = parsePriceYuanToCents(minPriceCommitted);
  const maxPriceCents = parsePriceYuanToCents(maxPriceCommitted);
  const sort = parseSortParam(searchParams.get('sort'));
  // Browse pagination (backlog #144): ?page= rides the same URL pattern
  // as the filters — refresh-safe and shareable. Page 0 is the default
  // and leaves no param behind, exactly like ?sort=newest.
  const page = parsePageParam(searchParams.get('page'));
  const categoriesQuery = useCategories();
  const { data, isLoading, isError } = useItems(page, searchQuery, categoryId, {
    minPriceCents,
    maxPriceCents,
    sort,
  });

  const setPage = (nextPage: number) => {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (nextPage <= 0) {
          next.delete('page');
        } else {
          next.set('page', String(nextPage));
        }
        return next;
      },
      { replace: true },
    );
  };

  const setSort = (value: string) => {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value === '' || value === 'newest') {
          next.delete('sort');
        } else {
          next.set('sort', value);
        }
        next.delete('page');
        return next;
      },
      { replace: true },
    );
  };

  // The category filter is URL-synced exactly like ?q= (#15): refresh-safe
  // and shareable, and it composes with the search box — changing one keeps
  // the other. A select needs no debounce, so this writes immediately.
  const setCategoryId = (value: string) => {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value === '') {
          next.delete('categoryId');
        } else {
          next.set('categoryId', value);
        }
        next.delete('page');
        return next;
      },
      { replace: true },
    );
  };

  const anyFilter =
    searchQuery !== '' ||
    categoryId !== null ||
    minPriceCents !== null ||
    maxPriceCents !== null;
  // "Save this search" (backlog #164): offered to signed-in visitors
  // whenever the current filter set differs from the default browse —
  // any of the #96 filters, or a non-default sort on its own.
  const user = useAuthStore((s) => s.user);
  const showSaveSearch = user !== null && (anyFilter || sort !== 'newest');
  // Search suggestions (backlog #235): the dropdown query is the live
  // input, debounced 300 ms like the #15 URL commit, so typing never
  // fires a request per keystroke. The query stays disabled signed out
  // — anonymous callers get 401 from the chain, like browse — and the
  // dropdown only opens on fresh suggestions: Escape or a selection
  // dismisses it, a new query re-arms it, and a selected title never
  // reopens it (the selected title is remembered until the input
  // changes again, so the just-picked suggestion does not pop back up
  // once its own suggestions land).
  const [debouncedSuggestQuery, setDebouncedSuggestQuery] = useState('');
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSuggestQuery(searchInput), 300);
    return () => clearTimeout(timer);
  }, [searchInput]);
  const [suggestionsDismissed, setSuggestionsDismissed] = useState(false);
  useEffect(() => {
    setSuggestionsDismissed(false);
  }, [debouncedSuggestQuery]);
  const [selectedSuggestion, setSelectedSuggestion] = useState<string | null>(null);
  useEffect(() => {
    if (selectedSuggestion !== null && searchInput !== selectedSuggestion) {
      setSelectedSuggestion(null);
    }
  }, [searchInput, selectedSuggestion]);
  const suggestionsQuery = useItemSuggestions(debouncedSuggestQuery, user !== null);
  const suggestionsOpen =
    !suggestionsDismissed &&
    debouncedSuggestQuery.trim() !== '' &&
    debouncedSuggestQuery !== selectedSuggestion &&
    (suggestionsQuery.data?.length ?? 0) > 0;
  const selectSuggestion = (title: string) => {
    setSelectedSuggestion(title);
    setSuggestionsDismissed(true);
    setSearchInput(title);
  };
  // "Recently viewed" strip (backlog #195): the signed-in visitor's
  // browsing trail, written by the detail read itself. The query is
  // disabled signed out (the endpoint is 401 for anonymous callers),
  // and the strip below renders only when the trail is non-empty —
  // a new visitor's home page carries no empty section.
  const recentlyViewed = useRecentlyViewed(user !== null);
  const emptyMessage = !anyFilter
    ? 'No items yet.'
    : searchQuery !== ''
      ? `No items match "${searchQuery}".`
      : 'No items match these filters.';

  return (
    <div>
      <h1 className="mb-5 text-3xl font-extrabold tracking-tight">Latest listings</h1>
      <div className="mb-6 flex flex-col gap-3 rounded-2xl border border-neutral-200/70 bg-white p-3 shadow-sm sm:flex-row dark:border-neutral-700 dark:bg-neutral-800">
        <div className="relative flex-1">
          <label htmlFor="search" className="sr-only">
            Search listings
          </label>
          <input
            id="search"
            type="search"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Escape') {
                setSuggestionsDismissed(true);
              }
            }}
            placeholder="Search listings…"
            className="w-full rounded-lg border border-neutral-200 bg-neutral-50 px-4 py-2.5 shadow-sm
                       focus:border-blue-500 focus:bg-white focus:outline-none focus:ring-4 focus:ring-blue-500/15"
          />
          {suggestionsOpen && (
            <SearchSuggestions
              suggestions={suggestionsQuery.data ?? []}
              onSelect={selectSuggestion}
              onDismiss={() => setSuggestionsDismissed(true)}
            />
          )}
        </div>
        <div>
          <label htmlFor="category-filter" className="sr-only">
            Filter by category
          </label>
          <select
            id="category-filter"
            value={categoryId === null ? '' : String(categoryId)}
            onChange={(e) => setCategoryId(e.target.value)}
            disabled={categoriesQuery.isLoading || categoriesQuery.isError}
            className="w-full rounded-lg border border-neutral-200 bg-neutral-50 px-4 py-2.5 shadow-sm
                       focus:border-blue-500 focus:bg-white focus:outline-none focus:ring-4 focus:ring-blue-500/15 sm:w-auto"
          >
            <option value="">All categories</option>
            {(categoriesQuery.data ?? []).map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
          {categoriesQuery.isError && (
            <p className="mt-1 text-sm text-neutral-500">
              Couldn&apos;t load categories — showing all listings.
            </p>
          )}
        </div>
        <div>
          <label htmlFor="min-price" className="sr-only">
            Minimum price (¥)
          </label>
          <input
            id="min-price"
            type="text"
            inputMode="decimal"
            value={minPriceInput}
            onChange={(e) => setMinPriceInput(e.target.value)}
            placeholder="Min ¥"
            className="w-full rounded-lg border border-neutral-200 bg-neutral-50 px-4 py-2.5 shadow-sm
                       focus:border-blue-500 focus:bg-white focus:outline-none focus:ring-4 focus:ring-blue-500/15 sm:w-28"
          />
        </div>
        <div>
          <label htmlFor="max-price" className="sr-only">
            Maximum price (¥)
          </label>
          <input
            id="max-price"
            type="text"
            inputMode="decimal"
            value={maxPriceInput}
            onChange={(e) => setMaxPriceInput(e.target.value)}
            placeholder="Max ¥"
            className="w-full rounded-lg border border-neutral-200 bg-neutral-50 px-4 py-2.5 shadow-sm
                       focus:border-blue-500 focus:bg-white focus:outline-none focus:ring-4 focus:ring-blue-500/15 sm:w-28"
          />
        </div>
        <div>
          <label htmlFor="sort-listings" className="sr-only">
            Sort listings
          </label>
          <select
            id="sort-listings"
            value={sort === 'newest' ? '' : sort}
            onChange={(e) => setSort(e.target.value)}
            className="w-full rounded-lg border border-neutral-200 bg-neutral-50 px-4 py-2.5 shadow-sm
                       focus:border-blue-500 focus:bg-white focus:outline-none focus:ring-4 focus:ring-blue-500/15 sm:w-auto"
          >
            <option value="">Newest first</option>
            <option value="price-asc">Price: low to high</option>
            <option value="price-desc">Price: high to low</option>
          </select>
        </div>
      </div>
      {showSaveSearch && (
        <SaveSearchButton
          filters={{
            keyword: searchQuery !== '' ? searchQuery : null,
            categoryId,
            minPriceCents,
            maxPriceCents,
            sort,
          }}
        />
      )}
      {user !== null && recentlyViewed.data && recentlyViewed.data.length > 0 && (
        <section aria-label="Recently viewed" className="mb-6">
          <h2 className="mb-3 text-xl font-extrabold tracking-tight">Recently viewed</h2>
          <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {recentlyViewed.data.map((item) => {
              const photoUrls = listingPhotoUrls(item);
              return (
                <li key={item.id} className="rounded-2xl border border-neutral-200/70 bg-white p-4 shadow-sm transition duration-200 hover:-translate-y-0.5 hover:shadow-lg dark:border-neutral-700 dark:bg-neutral-800">
                  {photoUrls.length > 0 ? (
                    <ListingPhoto
                      src={photoUrls[0]}
                      alt={item.title}
                      imgClassName="mb-3 h-40 w-full rounded object-cover"
                      placeholderClassName="mb-3 flex h-44 items-center justify-center rounded-xl bg-gradient-to-br from-blue-100/80 via-blue-50 to-neutral-100 text-sm text-neutral-500 dark:from-neutral-700 dark:to-neutral-800 dark:text-neutral-300"
                    />
                  ) : (
                    <div
                      data-testid="no-photo-placeholder"
                      className="mb-3 flex h-44 flex-col items-center justify-center gap-2 rounded-xl bg-gradient-to-br from-blue-100/80 via-blue-50 to-neutral-100 text-sm text-neutral-500 dark:from-neutral-700 dark:to-neutral-800 dark:text-neutral-300"
                    >
                      <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-8 w-8 text-neutral-400 dark:text-neutral-400">
                        <path strokeLinecap="round" strokeLinejoin="round" d="M2.25 15.75l5.159-5.159a2.25 2.25 0 013.182 0l5.159 5.159m-1.5-1.5l1.409-1.409a2.25 2.25 0 013.182 0l2.909 2.909m-18 3.75h16.5a1.5 1.5 0 001.5-1.5V6a1.5 1.5 0 00-1.5-1.5H3.75A1.5 1.5 0 002.25 6v12a1.5 1.5 0 001.5 1.5zm10.5-11.25h.008v.008h-.008V8.25zm.375 0a.375.375 0 11-.75 0 .375.375 0 01.75 0z" />
                      </svg>
                      No photo
                    </div>
                  )}
                  <Link to={`/items/${item.id}`} className="font-semibold hover:underline">
                    {item.title}
                  </Link>
                  <p className="mt-1.5 text-lg font-bold tracking-tight text-neutral-900 dark:text-neutral-50">
                    ¥{(item.priceCents / 100).toFixed(2)}
                </p>
                  <span className="mt-2 inline-block rounded-full bg-blue-50 px-2.5 py-0.5 text-xs font-medium text-blue-700 dark:bg-neutral-700 dark:text-neutral-200">
                    {item.status}
                  </span>{' '}
                  <FavoriteButton itemId={item.id} />
                </li>
              );
            })}
          </ul>
        </section>
      )}
      {isLoading && <p>Loading listings…</p>}
      {isError && <p className="text-red-600">Failed to load listings.</p>}
      {data && (
        <>
          {data.content.length === 0 && <p>{emptyMessage}</p>}
          <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {data.content.map((item) => {
              const photoUrls = listingPhotoUrls(item);
              return (
              <li key={item.id} className="rounded-2xl border border-neutral-200/70 bg-white p-4 shadow-sm transition duration-200 hover:-translate-y-0.5 hover:shadow-lg dark:border-neutral-700 dark:bg-neutral-800">
                {photoUrls.length > 0 ? (
                  <ListingPhoto
                    src={photoUrls[0]}
                    alt={item.title}
                    imgClassName="mb-3 h-40 w-full rounded object-cover"
                    placeholderClassName="mb-3 flex h-44 items-center justify-center rounded-xl bg-gradient-to-br from-blue-100/80 via-blue-50 to-neutral-100 text-sm text-neutral-500 dark:from-neutral-700 dark:to-neutral-800 dark:text-neutral-300"
                  />
                ) : (
                  <div
                    data-testid="no-photo-placeholder"
                    className="mb-3 flex h-44 flex-col items-center justify-center gap-2 rounded-xl bg-gradient-to-br from-blue-100/80 via-blue-50 to-neutral-100 text-sm text-neutral-500 dark:from-neutral-700 dark:to-neutral-800 dark:text-neutral-300"
                  >
                    <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-8 w-8 text-neutral-400 dark:text-neutral-400">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M2.25 15.75l5.159-5.159a2.25 2.25 0 013.182 0l5.159 5.159m-1.5-1.5l1.409-1.409a2.25 2.25 0 013.182 0l2.909 2.909m-18 3.75h16.5a1.5 1.5 0 001.5-1.5V6a1.5 1.5 0 00-1.5-1.5H3.75A1.5 1.5 0 002.25 6v12a1.5 1.5 0 001.5 1.5zm10.5-11.25h.008v.008h-.008V8.25zm.375 0a.375.375 0 11-.75 0 .375.375 0 01.75 0z" />
                    </svg>
                    No photo
                  </div>
                )}
                <Link to={`/items/${item.id}`} className="font-semibold hover:underline">
                  {item.title}
                </Link>
                <p className="mt-1.5 text-lg font-bold tracking-tight text-neutral-900 dark:text-neutral-50">
                  ¥{(item.priceCents / 100).toFixed(2)}
                  </p>
                <span className="mt-2 inline-block rounded-full bg-blue-50 px-2.5 py-0.5 text-xs font-medium text-blue-700 dark:bg-neutral-700 dark:text-neutral-200">
                  {item.status}
                </span>{' '}
                <FavoriteButton itemId={item.id} />
              </li>
              );
            })}
          </ul>
          {data.totalPages > 0 && (
            <nav aria-label="Listing pages" className="mt-6 flex items-center justify-center gap-4">
              <button
                type="button"
                onClick={() => setPage(page - 1)}
                disabled={page <= 0}
                className="rounded-full bg-neutral-900 px-5 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-neutral-700 disabled:opacity-50"
              >
                Previous
              </button>
              <span>
                Page {page + 1} of {data.totalPages}
              </span>
              <button
                type="button"
                onClick={() => setPage(page + 1)}
                disabled={page + 1 >= data.totalPages}
                className="rounded-full bg-neutral-900 px-5 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-neutral-700 disabled:opacity-50"
              >
                Next
              </button>
            </nav>
          )}
        </>
      )}
    </div>
  );
}
