import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';

/**
 * Accessibility (backlog #167): on every client-side route change, move
 * focus to the new page's <h1> inside <main>.
 *
 * Without this, a keyboard / screen-reader user who follows a link
 * keeps focus wherever it was in the old page (often on a link that no
 * longer exists), and the new page is announced from wherever the
 * virtual cursor happens to land instead of from its heading.
 *
 * Deliberate choices:
 * - The initial page load is skipped — the browser / assistive tech
 *   owns first paint; this only handles in-app navigation.
 * - Keyed on pathname only: a query-string change on the same page
 *   (e.g. the browse search syncing ?q= while the user types) must
 *   never steal focus from the field being typed in.
 * - Headings are not focusable by default, so the found <h1> gets
 *   tabindex="-1" (programmatically focusable, never added to the tab
 *   order) when it does not already carry a tabindex.
 * - Pages are lazy chunks (see main.tsx): the heading may not exist
 *   yet when the pathname changes, so a MutationObserver on <main>
 *   waits for it, bounded by a 3-second timeout — a page with no <h1>
 *   simply leaves focus alone.
 */
export default function RouteFocusManager() {
  const { pathname } = useLocation();
  const isInitialRender = useRef(true);

  useEffect(() => {
    if (isInitialRender.current) {
      isInitialRender.current = false;
      return;
    }

    const main = document.querySelector('main');
    if (!main) return;

    let observer: MutationObserver | null = null;
    let timer: number | undefined;

    const stopWaiting = () => {
      observer?.disconnect();
      observer = null;
      if (timer !== undefined) {
        window.clearTimeout(timer);
        timer = undefined;
      }
    };

    const tryFocusHeading = (): boolean => {
      const heading = main.querySelector('h1');
      if (!heading) return false;
      if (!heading.hasAttribute('tabindex')) {
        heading.setAttribute('tabindex', '-1');
      }
      heading.focus();
      return true;
    };

    if (!tryFocusHeading()) {
      observer = new MutationObserver(() => {
        if (tryFocusHeading()) stopWaiting();
      });
      observer.observe(main, { childList: true, subtree: true });
      timer = window.setTimeout(stopWaiting, 3000);
    }

    return stopWaiting;
  }, [pathname]);

  return null;
}
