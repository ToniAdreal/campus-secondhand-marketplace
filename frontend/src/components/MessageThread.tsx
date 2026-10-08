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
 * Scope notes: the send box always addresses the seller (buyer → seller),
 * so the seller sees the thread read-only — per-recipient replies are a
 * follow-up. The thread renders newest-first pages (backend page 0 is the
 * newest page); "Load earlier messages" fetches older pages and prepends
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

  if (user === null) return null;

  const isSeller = user.id === sellerId;
  const hiddenByPrivacy = isError && isForbidden(error);
  const messages = stitchThreadPages(data?.pages ?? []);

  const handleSend = (e: React.FormEvent) => {
    e.preventDefault();
    const body = draft.trim();
    if (body === '') return;
    setSendError(null);
    sendMessage.mutate(
      { itemId, receiverId: sellerId, body },
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

      {!isSeller && (
        <form onSubmit={handleSend} className="mt-4">
          <label htmlFor="message-draft" className="mb-1 block text-sm font-medium">
            Message the seller
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
