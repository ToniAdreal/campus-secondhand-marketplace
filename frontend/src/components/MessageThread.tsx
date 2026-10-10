import { useState } from 'react';
import { describeMessageError, stitchThreadPages, useMessageThread, useSendMessage } from '../api/messages';
import { useAuthStore } from '../store/useAuthStore';

interface MessageThreadProps {
  itemId: number;
  sellerId: number;
}

function isForbidden(error: unknown): boolean {
  const status = (error as { response?: { status?: number } }).response?.status;
  return status === 403;
}

/**
 * Buyer↔seller conversation for one listing (backend POST/GET
 * /api/messages). The thread is readable only by participants: a 403
 * means the caller never participated, so the thread stays hidden behind
 * a privacy note instead of an empty list — that is the backend's rule,
 * mirrored here.
 *
 * Addressing (backlog #112): a buyer always writes to the seller; the
 * seller replies per buyer. The seller's thread query returns every
 * conversation on the listing (the seller participates in all of
 * them), so the component groups those messages per buyer — the other
 * participant of each message, derived from the thread itself, never a
 * free-form id — and renders one buyer thread at a time with a picker
 * when several buyers wrote. The reply box addresses the selected
 * buyer, which is exactly the pair the backend send guard accepts
 * (seller → buyer requires an existing thread on this listing).
 * The thread renders newest-first pages (backend page 0 is the newest
 * page); "Load earlier messages" fetches older pages and prepends
 * them, so the newest page stays anchored at the bottom.
 */
export default function MessageThread({ itemId, sellerId }: MessageThreadProps) {
  const user = useAuthStore((s) => s.user);
  const {
    data,
    isLoading,
    isError,
    error,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useMessageThread(itemId);
  const sendMessage = useSendMessage();
  const [draft, setDraft] = useState('');
  const [sendError, setSendError] = useState<string | null>(null);
  const [pickedBuyerId, setPickedBuyerId] = useState<number | null>(null);

  if (user === null) return null;

  const isSeller = user.id === sellerId;
  const hiddenByPrivacy = isError && isForbidden(error);
  const allMessages = stitchThreadPages(data?.pages ?? []);

  // Per-buyer grouping for the seller: the counterpart of a message is
  // its non-seller participant. Buyer ids appear in first-contact
  // (chronological) order.
  const buyerIds: number[] = [];
  for (const m of allMessages) {
    const counterpart = m.senderId === sellerId ? m.receiverId : m.senderId;
    if (counterpart !== sellerId && !buyerIds.includes(counterpart)) {
      buyerIds.push(counterpart);
    }
  }
  const selectedBuyerId = isSeller
    ? pickedBuyerId !== null && buyerIds.includes(pickedBuyerId)
      ? pickedBuyerId
      : (buyerIds[0] ?? null)
    : null;
  const messages = isSeller
    ? allMessages.filter(
        (m) =>
          selectedBuyerId !== null &&
          (m.senderId === selectedBuyerId || m.receiverId === selectedBuyerId),
      )
    : allMessages;

  const receiverId = isSeller ? selectedBuyerId : sellerId;
  const sendLabel = isSeller ? `Reply to User #${selectedBuyerId}` : 'Message the seller';

  const handleSend = (e: React.FormEvent) => {
    e.preventDefault();
    const body = draft.trim();
    if (body === '' || receiverId === null) return;
    setSendError(null);
    sendMessage.mutate(
      { itemId, receiverId, body },
      {
        onSuccess: () => setDraft(''),
        onError: (err) => setSendError(describeMessageError(err)),
      },
    );
  };

  return (
    <section aria-label="Messages about this listing">
      <h2 className="text-lg font-semibold">Messages</h2>

      {isLoading && <p className="mt-2 text-sm text-neutral-500">Loading messages…</p>}

      {hiddenByPrivacy && (
        <p className="mt-2 text-sm text-neutral-600">
          This conversation is private — only participants can see it.
        </p>
      )}

      {isError && !hiddenByPrivacy && (
        <p role="alert" className="mt-2 text-sm text-red-600">
          Couldn't load messages. Please try again.
        </p>
      )}

      {isSeller && buyerIds.length > 1 && (
        <div className="mt-3 flex flex-wrap gap-2" aria-label="Buyer threads">
          {buyerIds.map((buyerId) => (
            <button
              key={buyerId}
              type="button"
              aria-pressed={buyerId === selectedBuyerId}
              onClick={() => setPickedBuyerId(buyerId)}
              className={`rounded px-3 py-1 text-sm font-medium ${
                buyerId === selectedBuyerId
                  ? 'bg-blue-600 text-white'
                  : 'bg-neutral-100 text-neutral-800 hover:bg-neutral-200'
              }`}
            >
              {`User #${buyerId}`}
            </button>
          ))}
        </div>
      )}

      {messages.length > 0 && (
        <>
          {hasNextPage && !isError && (
            <button
              type="button"
              onClick={() => fetchNextPage()}
              disabled={isFetchingNextPage}
              className="mt-3 text-sm font-medium text-blue-600 hover:underline disabled:opacity-50"
            >
              {isFetchingNextPage ? 'Loading…' : 'Load earlier messages'}
            </button>
          )}
          <ul className="mt-3 space-y-3">
            {messages.map((m) => {
              const mine = m.senderId === user.id;
              return (
                <li key={m.id} className={mine ? 'text-right' : 'text-left'}>
                  <span className="text-xs text-neutral-500">
                    {mine ? 'You' : m.senderId === sellerId ? 'Seller' : `User #${m.senderId}`}
                  </span>
                  <p
                    className={`mt-0.5 inline-block max-w-full rounded-lg px-3 py-2 text-sm ${
                      mine ? 'bg-blue-600 text-white' : 'bg-neutral-100 text-neutral-800'
                    }`}
                  >
                    {m.body}
                  </p>
                </li>
              );
            })}
          </ul>
        </>
      )}

      {receiverId !== null && (
        <form onSubmit={handleSend} className="mt-4">
          <label htmlFor="message-draft" className="mb-1 block text-sm font-medium">
            {sendLabel}
          </label>
          <textarea
            id="message-draft"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            maxLength={2000}
            rows={3}
            placeholder="Ask about pickup, condition, price…"
            className="w-full rounded border px-3 py-2 text-sm"
          />
          <button
            type="submit"
            disabled={sendMessage.isLoading || draft.trim() === ''}
            className="mt-2 rounded bg-blue-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {sendMessage.isLoading ? 'Sending…' : 'Send'}
          </button>
          {sendError && (
            <p role="alert" className="mt-2 text-sm text-red-600">
              {sendError}
            </p>
          )}
        </form>
      )}
    </section>
  );
}
