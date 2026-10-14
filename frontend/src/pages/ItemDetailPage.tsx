import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  describeMarkSoldError,
  listingPhotoUrls,
  useItem,
  useMarkSold,
} from '../api/items';
import { describeOrderError, useCreateOrder } from '../api/orders';
import {
  REPORT_REASONS,
  describeReportError,
  useCreateReport,
  type ReportReason,
} from '../api/reports';
import { useAuthStore } from '../store/useAuthStore';
import FavoriteButton from '../components/FavoriteButton';
import ListingGallery from '../components/ListingGallery';
import MessageThread from '../components/MessageThread';

export default function ItemDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const { data, isLoading, isError } = useItem(Number(id));
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const createOrder = useCreateOrder();
  const markSold = useMarkSold();
  // Report dialog (backlog #160): local form state — reason defaults to
  // the first allowlisted value so the select always carries a valid
  // backend enum; on success the dialog is replaced by a plain
  // confirmation note (no auto-hide exists — a human ADMIN decides).
  const [reportOpen, setReportOpen] = useState(false);
  const [reportReason, setReportReason] = useState<ReportReason>('SPAM');
  const [reportNote, setReportNote] = useState('');
  const [reportError, setReportError] = useState<string | null>(null);
  const [reportDone, setReportDone] = useState(false);
  const createReport = useCreateReport();

  if (isLoading) return <p>Loading…</p>;
  if (isError || !data) return <p className="text-red-600">Item not found.</p>;

  const isSeller = user !== null && user.id === data.sellerId;
  const isAdmin = user !== null && user.roles.includes('ADMIN');
  const canBuy = user !== null && !isSeller && data.status === 'AVAILABLE';
  // Mirrors the backend's ItemSecurity.canMarkSold: the listing's seller or
  // an ADMIN may mark it SOLD. SOLD is terminal server-side, so the button
  // is only offered on AVAILABLE/RESERVED listings.
  const canMarkSold =
    user !== null &&
    (isSeller || isAdmin) &&
    (data.status === 'AVAILABLE' || data.status === 'RESERVED');

  const handleBuy = () => {
    setErrorMessage(null);
    createOrder.mutate(data.id, {
      onSuccess: (order) => navigate(`/orders/${order.id}`),
      onError: (error) => setErrorMessage(describeOrderError(error)),
    });
  };

  // Mirrors the backend rule: any signed-in user except the seller may
  // report (the seller reporting their own listing is a 422 server-side).
  const canReport = user !== null && !isSeller;

  const handleReport = () => {
    setReportError(null);
    createReport.mutate(
      { itemId: data.id, reason: reportReason, note: reportNote },
      {
        onSuccess: () => {
          setReportOpen(false);
          setReportDone(true);
        },
        onError: (error) => setReportError(describeReportError(error)),
      },
    );
  };

  const handleMarkSold = () => {
    setErrorMessage(null);
    // SOLD is terminal on the backend (no onward transition), so the
    // invalidated detail refetch shows SOLD and the button disappears.
    markSold.mutate(data.id, {
      onError: (error) => setErrorMessage(describeMarkSoldError(error)),
    });
  };

  return (
    <article className="rounded-lg bg-white p-6 shadow-sm">
      <h1 className="text-2xl font-bold">{data.title}</h1>
      <div className="mt-4">
        <ListingGallery title={data.title} photoUrls={listingPhotoUrls(data)} />
      </div>
      <p className="mt-2 text-lg">¥{(data.priceCents / 100).toFixed(2)}</p>
      <p className="mt-4 whitespace-pre-wrap text-neutral-700">{data.description}</p>
      <span className="mt-4 inline-block rounded bg-neutral-200 px-2 py-0.5 text-xs">
        {data.status}
      </span>{' '}
      <FavoriteButton itemId={data.id} />
      {data.sellerUsername && (
        <p className="mt-2 text-sm text-neutral-600">Sold by {data.sellerUsername}</p>
      )}

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
        {canMarkSold && (
          <div className="mt-4">
            <button
              type="button"
              onClick={handleMarkSold}
              disabled={markSold.isLoading}
              className="rounded bg-neutral-800 px-5 py-2 font-medium text-white hover:bg-neutral-900 disabled:opacity-50"
            >
              {markSold.isLoading ? 'Marking…' : 'Mark as sold'}
            </button>
            <p className="mt-2 text-sm text-neutral-500">
              Sold is final on this demo — the listing stops being offered.
            </p>
          </div>
        )}
        {errorMessage && (
          <p role="alert" className="mt-2 text-sm text-red-600">
            {errorMessage}
          </p>
        )}
      </div>

      {canReport && !reportDone && !reportOpen && (
        <div className="mt-4">
          <button
            type="button"
            onClick={() => setReportOpen(true)}
            className="text-sm text-neutral-500 underline hover:text-neutral-700"
          >
            Report this listing
          </button>
        </div>
      )}
      {canReport && reportDone && (
        <p className="mt-4 text-sm text-neutral-600">
          Report submitted — a moderator will review this listing. It stays visible until a
          moderator acts on it.
        </p>
      )}
      {canReport && reportOpen && (
        <div className="mt-4 rounded-lg border border-neutral-200 bg-neutral-50 p-4">
          <h2 className="text-sm font-semibold">Report this listing</h2>
          <div className="mt-3">
            <label htmlFor="report-reason" className="mb-1 block text-sm font-medium">
              Reason
            </label>
            <select
              id="report-reason"
              value={reportReason}
              onChange={(e) => setReportReason(e.target.value as ReportReason)}
              className="w-full rounded-lg border border-neutral-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-sky-500 focus:outline-none"
            >
              {REPORT_REASONS.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </div>
          <div className="mt-3">
            <label htmlFor="report-note" className="mb-1 block text-sm font-medium">
              Details (optional)
            </label>
            <textarea
              id="report-note"
              value={reportNote}
              onChange={(e) => setReportNote(e.target.value)}
              rows={3}
              maxLength={1000}
              className="w-full rounded-lg border border-neutral-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-sky-500 focus:outline-none"
            />
          </div>
          {reportError && (
            <p role="alert" className="mt-2 text-sm text-red-600">
              {reportError}
            </p>
          )}
          <div className="mt-3 flex gap-3">
            <button
              type="button"
              onClick={handleReport}
              disabled={createReport.isLoading}
              className="rounded bg-red-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
            >
              {createReport.isLoading ? 'Submitting…' : 'Submit report'}
            </button>
            <button
              type="button"
              onClick={() => setReportOpen(false)}
              className="rounded bg-neutral-200 px-4 py-1.5 text-sm font-medium text-neutral-800 hover:bg-neutral-300"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      {user && (
        <div className="mt-8 border-t pt-6">
          <MessageThread itemId={data.id} sellerId={data.sellerId} />
        </div>
      )}
    </article>
  );
}
