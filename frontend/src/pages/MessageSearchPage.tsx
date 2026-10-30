import { Link } from 'react-router-dom';
import { describeMessageError, useMessageSearch } from '../api/messages';
import { useDebouncedSearchParam } from '../hooks/useDebouncedSearchParam';

/**
 * Message search (backlog #273) over the #260 endpoint: the caller's own
 * threads, body text only. The query is debounced and URL-synced like
 * the browse search (300 ms, `?q=`), so a refresh or a shared link
 * reproduces the same search. A blank query renders the empty state —
 * never an error and never a request (the hook stays disabled). Results
 * link back to the owning item thread, which lives on the item detail
 * page. Blocked participants are not filtered here, following the #260
 * thread-read rule. Errors render through the existing
 * describeMessageError path, so a 401/403 reads like the other message
 * failures instead of leaking the envelope.
 */
export default function MessageSearchPage() {
  const [searchInput, searchQuery, setSearchInput] = useDebouncedSearchParam('q', 300);
  const search = useMessageSearch(searchQuery);
  const results = search.data?.pages.flatMap((page) => page.content) ?? [];

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">Messages</h1>
      <label className="mb-4 block">
        <span className="mb-1 block text-sm font-medium">Search your messages</span>
        <input
          type="search"
          value={searchInput}
          onChange={(event) => setSearchInput(event.target.value)}
          placeholder="Search message text…"
          className="w-full rounded border border-neutral-300 px-3 py-2 dark:border-neutral-700 dark:bg-neutral-800"
        />
      </label>
      {searchQuery === '' && (
        <p className="text-neutral-600 dark:text-neutral-400">
          Type a word above to search your messages across all conversations.
        </p>
      )}
      {searchQuery !== '' && search.isLoading && <p>Loading messages…</p>}
      {searchQuery !== '' && search.isError && (
        <p role="alert" className="text-red-600">
          {describeMessageError(search.error)}
        </p>
      )}
      {searchQuery !== '' && search.isSuccess && results.length === 0 && (
        <p className="text-neutral-600 dark:text-neutral-400">
          No messages match “{searchQuery}”.
        </p>
      )}
      {results.length > 0 && (
        <>
          <ul className="space-y-3">
            {results.map((message) => (
              <li key={message.id} className="rounded-lg bg-white p-4 shadow-sm dark:bg-neutral-900">
                <p className="whitespace-pre-wrap">{message.body}</p>
                <div className="mt-2 flex flex-wrap items-center gap-4 text-sm text-neutral-500">
                  <span>{new Date(message.createdAt).toLocaleString()}</span>
                  <Link
                    to={`/items/${message.itemId}`}
                    className="text-blue-600 hover:underline"
                  >
                    View conversation
                  </Link>
                </div>
              </li>
            ))}
          </ul>
          {search.hasNextPage && (
            <button
              type="button"
              onClick={() => search.fetchNextPage()}
              disabled={search.isFetchingNextPage}
              className="mt-4 rounded bg-blue-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
            >
              {search.isFetchingNextPage ? 'Loading…' : 'Load more'}
            </button>
          )}
        </>
      )}
    </div>
  );
}
