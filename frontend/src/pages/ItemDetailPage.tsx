import { useParams } from 'react-router-dom';
import { useItem } from '../api/items';

export default function ItemDetailPage() {
  const { id } = useParams();
  const { data, isLoading, isError } = useItem(Number(id));

  if (isLoading) return <p>Loading…</p>;
  if (isError || !data) return <p className="text-red-600">Item not found.</p>;

  return (
    <article className="rounded-lg bg-white p-6 shadow-sm">
      <h1 className="text-2xl font-bold">{data.title}</h1>
      <p className="mt-2 text-lg">¥{(data.priceCents / 100).toFixed(2)}</p>
      <p className="mt-4 whitespace-pre-wrap text-neutral-700">{data.description}</p>
      <span className="mt-4 inline-block rounded bg-neutral-200 px-2 py-0.5 text-xs">
        {data.status}
      </span>
    </article>
  );
}
