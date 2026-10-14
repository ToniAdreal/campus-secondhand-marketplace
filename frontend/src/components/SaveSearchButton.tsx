import { useState } from 'react';
import { useCreateSavedSearch, type SavedSearchInput } from '../api/savedSearches';

/**
 * "Save this search" for the browse page (backlog #164). Rendered by
 * HomePage only for signed-in visitors with an active filter set (the
 * backend would 401 an anonymous save anyway). On success the button
 * confirms in place; the saved row itself lives in the favorites
 * page's saved-searches section, whose query the mutation invalidates.
 * A 422 from the backend is the per-user cap of 20 — its message is
 * shown verbatim in the alert line.
 */
export default function SaveSearchButton({ filters }: { filters: SavedSearchInput }) {
  const create = useCreateSavedSearch();
  const [saved, setSaved] = useState(false);

  return (
    <div className="mb-4">
      <button
        type="button"
        disabled={create.isLoading}
        onClick={() =>
          create.mutate(filters, {
            onSuccess: () => setSaved(true),
          })
        }
        className="rounded bg-neutral-800 px-4 py-2 text-sm font-medium text-white hover:bg-neutral-700 disabled:opacity-50"
      >
        Save this search
      </button>
      {saved && !create.isError && (
        <span className="ml-3 text-sm text-neutral-600">
          Saved — find it under Saved searches on your favorites page.
        </span>
      )}
      {create.isError && (
        <span role="alert" className="ml-3 text-sm text-red-600">
          Couldn&apos;t save this search. You may have reached the limit of 20 saved searches.
        </span>
      )}
    </div>
  );
}
