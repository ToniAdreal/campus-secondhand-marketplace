import { Link } from 'react-router-dom';
import { useItems } from '../api/items';

export default function HomePage() {
  const { data, isLoading, isError } = useItems();

  if (isLoading) return <p>Loading listings…</p>;
  if (isError) return <p className="text-red-600">Failed to load listings.</p>;

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">Latest listings</h1>
      {data!.content.length === 0 && <p>No items yet.</p>}
      <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {data!.content.map((item) => (
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
    </div>
  );
}
