import { useState } from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import {
  describeRenewError,
  isListingExpired,
  isListingExpiringSoon,
  listingPhotoUrls,
  useMyListings,
  useRenewListing,
} from '../api/items';
import {
  describeAppealError,
  useCreateAppeal,
  useSellerReports,
  type SellerReport,
} from '../api/appeals';
import { useAuthStore } from '../store/useAuthStore';

/**
 * "My listings" seller view (backlog #146) — GET /api/items?mine=true
 * rendered with the same card shape as the browse grid (photo, title
 * link, price, status badge). Every status is listed, including
 * RESERVED and SOLD: finding a listing again to manage it is the whole
 * point, and before this page a seller could only hunt through the
 * public browse pages. Each card's edit link goes to the listing's
 * detail page, which hosts the seller actions (mark as sold). Anonymous
 * visitors bounce to /login like the other account pages.
 */
export default function MyListingsPage() {
  const location = useLocation();
  const user = useAuthStore((s) => s.user);
  const [page, setPage] = useState(0);
  const listings = useMyListings(page);
  const renew = useRenewListing();
  const [renewError, setRenewError] = useState<string | null>(null);
  // Report appeals (backlog #190): reports on the caller's own
  // listings, each with its appeal state — an ACTIONED report is what
  // withdrew a listing, and its appeal is the seller's recourse.
  const sellerReports = useSellerReports(user !== null);
  const createAppeal = useCreateAppeal();
  const [appealReasons, setAppealReasons] = useState<Record<number, string>>({});
  const [appealError, setAppealError] = useState<string | null>(null);

  if (!user) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  const data = listings.data;
  const actionedReportsByItem = new Map<number, SellerReport[]>();
  for (const report of sellerReports.data?.content ?? []) {
    if (report.status !== 'ACTIONED') {
      continue;
    }
    const existing = actionedReportsByItem.get(report.itemId) ?? [];
    existing.push(report);
    actionedReportsByItem.set(report.itemId, existing);
  }

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">My listings</h1>
      {listings.isLoading && <p>Loading your listings…</p>}
      {listings.isError && (
        <p role="alert" className="text-red-600">
          Failed to load your listings.
        </p>
      )}
      {renewError && (
        <p role="alert" className="text-red-600">
          {renewError}
        </p>
      )}
      {appealError && (
        <p role="alert" className="text-red-600">
          {appealError}
        </p>
      )}
      {listings.isSuccess && data && data.content.length === 0 && (
        <div className="rounded-lg bg-white p-6 text-center shadow-sm">
          <p className="text-neutral-600">You haven&apos;t listed anything yet.</p>
          <Link to="/items/new" className="mt-2 inline-block text-sm text-blue-600 hover:underline">
            Sell an item
          </Link>
        </div>
      )}
      {data && data.content.length > 0 && (
        <>
          <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {data.content.map((item) => {
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
                  {isListingExpired(item) && (
                    <span className="mt-2 inline-block rounded bg-red-100 px-2 py-0.5 text-xs text-red-700">
                      Expired
                    </span>
                  )}{' '}
                  {item.expiresAt && (
                    <p className="mt-1 text-xs text-neutral-500">
                      {isListingExpired(item) ? 'Expired on ' : 'Expires '}
                      {new Date(item.expiresAt).toLocaleDateString()}
                    </p>
                  )}
                  {item.viewCount != null && (
                    <p className="mt-1 text-xs text-neutral-500">
                      {item.viewCount} {item.viewCount === 1 ? 'view' : 'views'}
                    </p>
                  )}
                  {(isListingExpired(item) || isListingExpiringSoon(item)) && (
                    <button
                      type="button"
                      disabled={renew.isLoading}
                      onClick={() => {
                        setRenewError(null);
                        renew.mutate(item.id, {
                          onError: (error) => setRenewError(describeRenewError(error)),
                        });
                      }}
                      className="mt-2 block rounded bg-neutral-800 px-3 py-1 text-sm font-medium text-white hover:bg-neutral-700 disabled:opacity-50"
                    >
                      Renew listing
                    </button>
                  )}
                  {(actionedReportsByItem.get(item.id) ?? []).map((report) => (
                    <div key={report.id} className="mt-2 rounded bg-red-50 p-3 text-sm">
                      <p className="text-red-700">
                        This listing was withdrawn after a report was actioned.
                      </p>
                      {report.appealStatus === null && (
                        <>
                          <label
                            htmlFor={`appeal-reason-${report.id}`}
                            className="mt-2 block text-xs font-medium text-neutral-700"
                          >
                            Why should this decision be reconsidered?
                          </label>
                          <textarea
                            id={`appeal-reason-${report.id}`}
                            aria-label="Appeal reason"
                            value={appealReasons[report.id] ?? ''}
                            onChange={(e) =>
                              setAppealReasons((prev) => ({
                                ...prev,
                                [report.id]: e.target.value,
                              }))
                            }
                            maxLength={500}
                            rows={3}
                            className="mt-1 w-full rounded border border-neutral-300 px-2 py-1 text-sm"
                          />
                          <button
                            type="button"
                            disabled={createAppeal.isLoading}
                            onClick={() => {
                              setAppealError(null);
                              createAppeal.mutate(
                                {
                                  reportId: report.id,
                                  reason: (appealReasons[report.id] ?? '').trim(),
                                },
                                {
                                  onError: (error) =>
                                    setAppealError(describeAppealError(error)),
                                },
                              );
                            }}
                            className="mt-2 block rounded bg-red-600 px-3 py-1 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
                          >
                            Appeal withdrawal
                          </button>
                        </>
                      )}
                      {report.appealStatus === 'OPEN' && (
                        <p className="mt-1 text-neutral-600">
                          Appeal pending — an admin is reviewing this withdrawal.
                        </p>
                      )}
                      {report.appealStatus === 'UPHELD' && (
                        <p className="mt-1 text-emerald-700">
                          {report.appealListingRestored
                            ? 'Appeal upheld — this listing was restored.'
                            : 'Appeal upheld — the listing had already moved on, so it was left as it was.'}
                        </p>
                      )}
                      {report.appealStatus === 'REJECTED' && (
                        <p className="mt-1 text-neutral-600">
                          Appeal rejected — the withdrawal stands.
                        </p>
                      )}
                    </div>
                  ))}
                  <Link
                    to={`/items/${item.id}`}
                    className="text-sm text-blue-600 hover:underline"
                  >
                    Edit listing
                  </Link>
                </li>
              );
            })}
          </ul>
          {data.totalPages > 1 && (
            <nav aria-label="My listing pages" className="mt-6 flex items-center justify-center gap-4">
              <button
                type="button"
                onClick={() => setPage(page - 1)}
                disabled={page <= 0}
                className="rounded bg-neutral-800 px-4 py-2 text-sm font-medium text-white hover:bg-neutral-700 disabled:opacity-50"
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
                className="rounded bg-neutral-800 px-4 py-2 text-sm font-medium text-white hover:bg-neutral-700 disabled:opacity-50"
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
