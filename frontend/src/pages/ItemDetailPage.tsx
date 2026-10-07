import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useItem } from '../api/items';
import { describeOrderError, useCreateOrder } from '../api/orders';
import { useAuthStore } from '../store/useAuthStore';
import MessageThread from '../components/MessageThread';

export default function ItemDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const { data, isLoading, isError } = useItem(Number(id));
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const createOrder = useCreateOrder();

  if (isLoading) return <p>Loading…</p>;
  if (isError || !data) return <p className="text-red-600">Item not found.</p>;

  const isSeller = user !== null && user.id === data.sellerId;
  const canBuy = user !== null && !isSeller && data.status === 'AVAILABLE';

  const handleBuy = () => {
    setErrorMessage(null);
    createOrder.mutate(data.id, {
      onSuccess: (order) => navigate(`/orders/${order.id}`),
      onError: (error) => setErrorMessage(describeOrderError(error)),
    });
  };

  return (
    <article className="rounded-lg bg-white p-6 shadow-sm">
      <h1 className="text-2xl font-bold">{data.title}</h1>
      <p className="mt-2 text-lg">¥{(data.priceCents / 100).toFixed(2)}</p>
      <p className="mt-4 whitespace-pre-wrap text-neutral-700">{data.description}</p>
      <span className="mt-4 inline-block rounded bg-neutral-200 px-2 py-0.5 text-xs">
        {data.status}
      </span>

      <div className="mt-6">
        {canBuy && (
          <button
            type="button"
            onClick={handleBuy}
            disabled={createOrder.isLoading}
            className="rounded bg-blue-600 px-5 py-2 font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {createOrder.isLoading ? 'Placing order…' : 'Buy now'}
          </button>
        )}
        {!user && data.status === 'AVAILABLE' && (
          <p className="text-sm text-neutral-600">
            <Link to="/login" className="text-blue-600 hover:underline">
              Log in
            </Link>{' '}
            to buy this item.
          </p>
        )}
        {errorMessage && (
          <p role="alert" className="mt-2 text-sm text-red-600">
            {errorMessage}
          </p>
        )}
      </div>

      {user && (
        <div className="mt-8 border-t pt-6">
          <MessageThread itemId={data.id} sellerId={data.sellerId} />
        </div>
      )}
    </article>
  );
}
