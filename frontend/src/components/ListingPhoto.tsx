import { useEffect, useState } from 'react';

/**
 * One listing/evidence photo with a broken-image fallback (backlog #233).
 * A photo URL that fails to load swaps the <img> for the same honest
 * "No photo" placeholder block the photo-less path renders — never a
 * stock or generated image, and never the browser's broken-image icon.
 * The caller supplies both class sets, so the placeholder occupies
 * exactly the slot the image would have (card thumbnails pass their
 * h-40 block classes, the gallery main image its h-48 block), and the
 * swap causes no layout shift. The failed flag resets when `src`
 * changes, so switching gallery thumbnails (or a refetch) can recover
 * a slot that failed for a previous URL.
 */
export default function ListingPhoto({
  src,
  alt,
  imgClassName,
  placeholderClassName,
}: {
  src: string;
  alt: string;
  imgClassName: string;
  placeholderClassName: string;
}) {
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    setFailed(false);
  }, [src]);

  if (failed) {
    return (
      <span
        data-testid="no-photo-placeholder"
        className={placeholderClassName}
      >
        No photo
      </span>
    );
  }

  return (
    <img
      src={src}
      alt={alt}
      className={imgClassName}
      onError={() => setFailed(true)}
    />
  );
}
