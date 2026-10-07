import { Link, useParams } from 'react-router-dom';
import { useOrder } from '../api/orders';

/**
 * Order confirmation — shown right after a successful buy-now. Reads the
 * order through the buyer order-reads endpoint (GET /api/orders/{id}).
 */
export default function OrderConfirmationPage() {
  const { id } = useParams();
  const { data, isLoading, isError } = useOrder(Number(id));

  if (isLoading) return <p>Loading…</p>;
  if (isError || !data) return <p className="text-red-600">Order not found.</p>;

  return (
    <section className="rounded-lg bg-white p-6 shadow-sm">
      <h1 className="text-2xl font-bold">Order confirmed</h1>
      <p className="mt-2 text-neutral-600">
        Order #{data.id} is {data.status.toLowerCase()}. The listing is now
        reserved for you.
      </p>
      <dl className="mt-4 space-y-1 text-sm">
        <div className="flex gap-2">
          <dt className="text-neutral-500">Amount:</dt>
          <dd>¥{(data.amountCents / 100).toFixed(2)}</dd>
        </div>
        <div className="flex gap-2">
          <dt className="text-neutral-500">Status:</dt>
          <dd>{data.status}</dd>
        </div>
      </dl>
      <p className="mt-4 text-sm text-neutral-500">
        Portfolio-demo note: no real payment is taken. The mock capture
        endpoint (POST /api/orders/{data.id}/pay) exists on the backend but is
        not wired to a button yet.
      </p>
      <Link to={`/items/${data.itemId}`} className="mt-4 inline-block text-sm text-blue-600 hover:underline">
        Back to the listing
      </Link>
    </section>
  );
}
