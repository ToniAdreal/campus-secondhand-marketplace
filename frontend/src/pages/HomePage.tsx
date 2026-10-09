import { Link, useSearchParams } from 'react-router-dom';
import { useItems } from '../api/items';
import { useCategories } from '../api/categories';
import { useDebouncedSearchParam } from '../hooks/useDebouncedSearchParam';

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

export default function HomePage() {
  const [searchInput, searchQuery, setSearchInput] = useDebouncedSearchParam('q', 300);
  // Price bounds ride the same debounced URL pattern, in yuan as typed
  // (?minPrice=/?maxPrice=); they are converted to cents for the API.
  const [minPriceInput, minPriceCommitted, setMinPriceInput] =
    useDebouncedSearchParam('minPrice', 300);
  const [maxPriceInput, maxPriceCommitted, setMaxPriceInput] =
    useDebouncedSearchParam('maxPrice', 300);
  const [searchParams, setSearchParams] = useSearchParams();
  const categoryId = parseCategoryId(searchParams.get('categoryId'));
  const minPriceCents = parsePriceYuanToCents(minPriceCommitted);
  const maxPriceCents = parsePriceYuanToCents(maxPriceCommitted);
  const sort = parseSortParam(searchParams.get('sort'));
  const categoriesQuery = useCategories();
  const { data, isLoading, isError } = useItems(0, searchQuery, categoryId, {
    minPriceCents,
    maxPriceCents,
    sort,
  });

  const setSort = (value: string) => {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value === '' || value === 'newest') {
          next.delete('sort');
        } else {
          next.set('sort', value);
        }
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
  const emptyMessage = !anyFilter
    ? 'No items yet.'
    : searchQuery !== ''
      ? `No items match "${searchQuery}".`
      : 'No items match these filters.';

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">Latest listings</h1>
      <div className="mb-4 flex flex-col gap-3 sm:flex-row">
        <div className="flex-1">
          <label htmlFor="search" className="sr-only">
            Search listings
          </label>
          <input
            id="search"
            type="search"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            placeholder="Search listings…"
            className="w-full rounded-lg border border-neutral-300 bg-white px-4 py-2 shadow-sm
                       focus:border-sky-500 focus:outline-none"
          />
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
            className="w-full rounded-lg border border-neutral-300 bg-white px-4 py-2 shadow-sm
                       focus:border-sky-500 focus:outline-none sm:w-auto"
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
            className="w-full rounded-lg border border-neutral-300 bg-white px-4 py-2 shadow-sm
                       focus:border-sky-500 focus:outline-none sm:w-28"
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
            className="w-full rounded-lg border border-neutral-300 bg-white px-4 py-2 shadow-sm
                       focus:border-sky-500 focus:outline-none sm:w-28"
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
            className="w-full rounded-lg border border-neutral-300 bg-white px-4 py-2 shadow-sm
                       focus:border-sky-500 focus:outline-none sm:w-auto"
          >
            <option value="">Newest first</option>
            <option value="price-asc">Price: low to high</option>
            <option value="price-desc">Price: high to low</option>
          </select>
        </div>
      </div>
      {isLoading && <p>Loading listings…</p>}
      {isError && <p className="text-red-600">Failed to load listings.</p>}
      {data && (
        <>
          {data.content.length === 0 && <p>{emptyMessage}</p>}
          <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {data.content.map((item) => (
              <li key={item.id} className="rounded-lg bg-white p-4 shadow-sm">
                <Link to={`/items/${item.id}`} className="font-semibold hover:underline">
                  {item.title}
                </Link>
                <p className="mt-1 text-sm text-neutral-500">
                  ¥{(item.priceCents / 100).toFixed(2)}
                </p>
                <span className="mt-2 inline-block rounded bg-neutral-200 px-2 py-0.5 text-xs">
                  {item.status}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}
