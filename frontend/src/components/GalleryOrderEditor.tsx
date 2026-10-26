import { useEffect, useState } from 'react';
import {
  describeReorderPhotosError,
  useReorderPhotos,
  type ItemPhoto,
} from '../api/items';
import ListingPhoto from './ListingPhoto';

/**
 * Seller gallery editor (backlog #162): reorder a listing's photos
 * with up/down buttons — keyboard-operable by construction (real
 * <button>s, no drag-only interaction). The first photo is the
 * primary photo (the one browse cards render), so the editor says
 * so on the first row. Local order state only becomes a request on
 * "Save photo order": the PUT payload is the complete ordered
 * photo-id list, which the backend requires to be an exact
 * permutation of the current gallery (a stale list is a 400, mapped
 * to friendly copy here — never a silent partial reorder).
 */
export default function GalleryOrderEditor({
  itemId,
  photos,
}: {
  itemId: number;
  photos: ItemPhoto[];
}) {
  const sorted = [...photos].sort((a, b) => a.position - b.position);
  const originalIds = sorted.map((photo) => photo.id);
  const [orderedIds, setOrderedIds] = useState<number[]>(originalIds);
  // The last order known to be the server's: the props' order, updated
  // to the just-saved order the moment a save succeeds (the parent's
  // invalidated refetch then confirms the same order back).
  const [baselineIds, setBaselineIds] = useState<number[]>(originalIds);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [savedNote, setSavedNote] = useState(false);
  const reorder = useReorderPhotos();

  // A refetch can swap the gallery under a mounted page (another tab
  // added/removed a photo); reset the local order to the server's
  // whenever the id SET changes, so the save payload can never carry
  // a photo the gallery no longer has. Keyed on the set, not the
  // order: an order-only refetch (e.g. the one this editor's own save
  // invalidates) must not wipe the local state or the saved note.
  const idSetKey = [...originalIds].sort((a, b) => a - b).join(',');
  useEffect(() => {
    setOrderedIds(originalIds);
    setBaselineIds(originalIds);
    setSavedNote(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [idSetKey]);

  const byId = new Map(sorted.map((photo) => [photo.id, photo]));
  const dirty = orderedIds.join(',') !== baselineIds.join(',');

  const move = (index: number, delta: number) => {
    const target = index + delta;
    if (target < 0 || target >= orderedIds.length) {
      return;
    }
    const next = [...orderedIds];
    [next[index], next[target]] = [next[target], next[index]];
    setOrderedIds(next);
    setSavedNote(false);
  };

  const handleSave = () => {
    setErrorMessage(null);
    reorder.mutate(
      { itemId, photoIds: orderedIds },
      {
        onSuccess: () => {
          setBaselineIds(orderedIds);
          setSavedNote(true);
        },
        onError: (error) => setErrorMessage(describeReorderPhotosError(error)),
      },
    );
  };

  if (sorted.length < 2) {
    return null;
  }

  return (
    <section aria-label="Photo order" className="mt-6 border-t pt-6">
      <h2 className="text-sm font-semibold">Photo order</h2>
      <p className="mt-1 text-sm text-neutral-500">
        The first photo is the primary photo shown on browse cards.
      </p>
      <ul className="mt-3 space-y-2">
        {orderedIds.map((photoId, index) => {
          const photo = byId.get(photoId);
          if (!photo) {
            return null;
          }
          return (
            <li
              key={photoId}
              className="flex items-center gap-3 rounded-lg border border-neutral-200 bg-neutral-50 p-2"
            >
              <ListingPhoto
                src={photo.url}
                alt=""
                imgClassName="h-12 w-12 rounded object-cover"
                placeholderClassName="flex h-12 w-12 items-center justify-center overflow-hidden rounded bg-neutral-100 text-[8px] text-neutral-500"
              />
              <span className="text-sm text-neutral-700">
                Photo {index + 1}
                {index === 0 && (
                  <span className="ml-2 rounded bg-sky-100 px-2 py-0.5 text-xs text-sky-800">
                    Primary
                  </span>
                )}
              </span>
              <span className="ml-auto flex gap-2">
                <button
                  type="button"
                  onClick={() => move(index, -1)}
                  disabled={index === 0}
                  aria-label={`Move photo ${index + 1} up`}
                  className="rounded bg-neutral-200 px-3 py-1 text-sm font-medium text-neutral-800 hover:bg-neutral-300 disabled:opacity-50"
                >
                  Move up
                </button>
                <button
                  type="button"
                  onClick={() => move(index, 1)}
                  disabled={index === orderedIds.length - 1}
                  aria-label={`Move photo ${index + 1} down`}
                  className="rounded bg-neutral-200 px-3 py-1 text-sm font-medium text-neutral-800 hover:bg-neutral-300 disabled:opacity-50"
                >
                  Move down
                </button>
              </span>
            </li>
          );
        })}
      </ul>
      {errorMessage && (
        <p role="alert" className="mt-2 text-sm text-red-600">
          {errorMessage}
        </p>
      )}
      {savedNote && !dirty && (
        <p className="mt-2 text-sm text-neutral-600">Photo order saved.</p>
      )}
      <button
        type="button"
        onClick={handleSave}
        disabled={!dirty || reorder.isLoading}
        className="mt-3 rounded bg-neutral-800 px-4 py-2 text-sm font-medium text-white hover:bg-neutral-900 disabled:opacity-50"
      >
        {reorder.isLoading ? 'Saving…' : 'Save photo order'}
      </button>
    </section>
  );
}
