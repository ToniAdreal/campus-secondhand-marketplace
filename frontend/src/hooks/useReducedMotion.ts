import { useEffect, useState } from 'react';

/**
 * Reduced-motion preference (backlog #427).
 *
 * Reads the OS `prefers-reduced-motion: reduce` media query and tracks it
 * live, so components that animate (the listing skeletons' shimmer) can
 * render a static placeholder instead when the user asks for less motion.
 * Guards mirror `systemPrefersDark` in theme.ts — jsdom and non-browser
 * contexts lack matchMedia, and the safe default there is "motion allowed".
 *
 * This is the first line of defence only: the shipped stylesheet's
 * #361 rule flattens every animation under the same media query, so a
 * missed hook still cannot animate. The hook exists so components can
 * *choose* not to animate (and tests can pin that choice) instead of
 * relying on the stylesheet to cancel the animation after the fact.
 */
export function useReducedMotion(): boolean {
  const [reduced, setReduced] = useState(() => prefersReducedMotion());
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
      return undefined;
    }
    const query = window.matchMedia('(prefers-reduced-motion: reduce)');
    const onChange = () => setReduced(query.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);
  return reduced;
}

function prefersReducedMotion(): boolean {
  try {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch {
    return false;
  }
}
