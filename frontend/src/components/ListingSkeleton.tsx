import { useReducedMotion } from '../hooks/useReducedMotion';

/**
 * Skeleton loading states (backlog #427).
 *
 * The browse grid and the detail page used to render a bare
 * `<p>Loading…</p>` while their queries were pending — a layout shift
 * when the rows arrived and nothing for a screen reader to hold on to.
 * These placeholders mirror the real layouts' dimensions so the swap
 * is in place:
 *
 * - `ListingGridSkeleton`: the browse card from HomePage
 *   (`rounded-[14px] border p-4`, photo `h-40`, title, `text-[20px]`
 *   price, status pill + favourite button) in the same
 *   `grid gap-4 sm:grid-cols-2 lg:grid-cols-3`. Six cards: two full
 *   rows at the large breakpoint, a first-screen placeholder rather
 *   than the whole 20-row page.
 * - `ListingDetailSkeleton`: the detail `article` (`rounded-lg bg-white
 *   p-6 shadow-sm`) — title, gallery block at the `ListingGallery`
 *   placeholder height (`h-48`) with thumbnail-sized bars, price,
 *   description lines, status pill, seller line, action button.
 *
 * Both carry `role="status"` with an accessible "Loading" label and
 * `aria-busy`, and their decorative bars are `aria-hidden`. The shimmer
 * sweep (`.skeleton-shimmer` in index.css) is applied only when the OS
 * does not ask for reduced motion — see `useReducedMotion`; the
 * stylesheet's #361 rule flattens animations anyway as a backstop.
 */

function SkeletonBar({ className, shimmer }: { className: string; shimmer: boolean }) {
  return (
    <div
      aria-hidden="true"
      className={`${className} bg-neutral-200 dark:bg-neutral-700${shimmer ? ' skeleton-shimmer' : ''}`}
    />
  );
}

export function ListingCardSkeleton({ shimmer }: { shimmer: boolean }) {
  return (
    <li
      data-testid="listing-card-skeleton"
      className="rounded-[14px] border border-apple-hairline bg-white p-4 dark:border-neutral-700 dark:bg-neutral-800"
    >
      <SkeletonBar className="mb-3 h-40 w-full rounded" shimmer={shimmer} />
      <SkeletonBar className="h-5 w-3/4 rounded" shimmer={shimmer} />
      <SkeletonBar className="mt-1.5 h-7 w-24 rounded" shimmer={shimmer} />
      <div className="mt-2 flex items-center gap-2">
        <SkeletonBar className="h-6 w-20 rounded-full" shimmer={shimmer} />
        <SkeletonBar className="h-8 w-8 rounded-full" shimmer={shimmer} />
      </div>
    </li>
  );
}

export function ListingGridSkeleton({ count = 6 }: { count?: number }) {
  const reducedMotion = useReducedMotion();
  return (
    <div role="status" aria-label="Loading listings" aria-busy="true">
      <span className="sr-only">Loading listings…</span>
      <ul aria-hidden="true" className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {Array.from({ length: count }, (_, i) => (
          <ListingCardSkeleton key={i} shimmer={!reducedMotion} />
        ))}
      </ul>
    </div>
  );
}

export function ListingDetailSkeleton() {
  const reducedMotion = useReducedMotion();
  const shimmer = !reducedMotion;
  return (
    <article
      data-testid="listing-detail-skeleton"
      className="rounded-lg bg-white p-6 shadow-sm"
      role="status"
      aria-label="Loading listing"
      aria-busy="true"
    >
      <span className="sr-only">Loading listing…</span>
      <div aria-hidden="true">
        <SkeletonBar className="h-8 w-2/3 rounded" shimmer={shimmer} />
        <SkeletonBar className="mt-4 h-48 w-full rounded-lg" shimmer={shimmer} />
        <div className="mt-3 flex gap-2">
          <SkeletonBar className="h-16 w-16 rounded" shimmer={shimmer} />
          <SkeletonBar className="h-16 w-16 rounded" shimmer={shimmer} />
          <SkeletonBar className="h-16 w-16 rounded" shimmer={shimmer} />
        </div>
        <SkeletonBar className="mt-2 h-7 w-28 rounded" shimmer={shimmer} />
        <SkeletonBar className="mt-4 h-4 w-full rounded" shimmer={shimmer} />
        <SkeletonBar className="mt-2 h-4 w-11/12 rounded" shimmer={shimmer} />
        <SkeletonBar className="mt-2 h-4 w-2/3 rounded" shimmer={shimmer} />
        <SkeletonBar className="mt-4 h-6 w-20 rounded" shimmer={shimmer} />
        <SkeletonBar className="mt-2 h-4 w-48 rounded" shimmer={shimmer} />
        <SkeletonBar className="mt-6 h-10 w-32 rounded" shimmer={shimmer} />
      </div>
    </article>
  );
}
