import { useEffect, useState } from 'react';
import ListingPhoto from './ListingPhoto';

/**
 * Photo gallery for the item detail page (backlog #133). The main image is
 * the primary photo (first URL — callers pass `listingPhotoUrls`, which is
 * position-ordered); thumbnails switch it. A listing with no photos renders
 * an honest "No photo" placeholder block — never a stock or generated
 * image, and never a broken <img> (backlog #233: a photo URL that fails
 * to load swaps to the same placeholder via ListingPhoto, main image
 * and thumbnails alike).
 */
export default function ListingGallery({
  title,
  photoUrls,
}: {
  title: string;
  photoUrls: string[];
}) {
  const [selected, setSelected] = useState(0);

  // A refetch can swap the gallery under a mounted page; never point the
  // selection past the end of the new list.
  useEffect(() => {
    setSelected(0);
  }, [photoUrls]);

  if (photoUrls.length === 0) {
    return (
      <div
        data-testid="no-photo-placeholder"
        className="flex h-48 items-center justify-center rounded-lg bg-neutral-100 text-sm text-neutral-500"
      >
        No photo
      </div>
    );
  }

  const index = Math.min(selected, photoUrls.length - 1);

  return (
    <div>
      <ListingPhoto
        src={photoUrls[index]}
        alt={title}
        imgClassName="max-h-96 w-full rounded-lg object-contain"
        placeholderClassName="flex h-48 items-center justify-center rounded-lg bg-neutral-100 text-sm text-neutral-500"
      />
      {photoUrls.length > 1 && (
        <div className="mt-3 flex gap-2">
          {photoUrls.map((url, i) => (
            <button
              key={`${url}-${i}`}
              type="button"
              onClick={() => setSelected(i)}
              aria-label={`Show photo ${i + 1} of ${photoUrls.length}`}
              aria-pressed={i === index}
              className={`rounded border-2 ${
                i === index ? 'border-sky-500' : 'border-transparent'
              }`}
            >
              <ListingPhoto
                src={url}
                alt=""
                imgClassName="h-16 w-16 rounded object-cover"
                placeholderClassName="flex h-16 w-16 items-center justify-center overflow-hidden rounded bg-neutral-100 text-[10px] text-neutral-500"
              />
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
