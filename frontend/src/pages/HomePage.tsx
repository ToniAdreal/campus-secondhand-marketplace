import { Link } from 'react-router-dom';
import { useItems } from '../api/items';
import { useDebouncedSearchParam } from '../hooks/useDebouncedSearchParam';

export default function HomePage() {
  const [searchInput, searchQuery, setSearchInput] = useDebouncedSearchParam('q', 300);
  const { data, isLoading, isError } = useItems(0, searchQuery);

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">Latest listings</h1>
      <div className="mb-4">
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
      {isLoading && <p>Loading listings…</p>}
      {isError && <p className="text-red-600">Failed to load listings.</p>}
      {data && (
        <>
          {data.content.length === 0 && (
            <p>{searchQuery === '' ? 'No items yet.' : `No items match "${searchQuery}".`}</p>
          )}
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
