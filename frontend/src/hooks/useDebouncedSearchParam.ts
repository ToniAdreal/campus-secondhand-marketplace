import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';

/**
 * Search state synced with a URL query parameter (default `q`).
 *
 * - Refresh-safe: the initial input and the committed search value are read
 *   from the URL, so a refresh or a shared link reproduces the same query.
 * - Typing updates the input immediately but only writes the (trimmed)
 *   value to the URL after {@code debounceMs} of inactivity, so we don't
 *   fire a request per keystroke. `replace: true` keeps the browser history
 *   clean — one back press leaves the search page.
 * - Clearing the input removes the parameter entirely instead of leaving
 *   `?q=` behind.
 *
 * Returns {@code [input, committed, update]}: the live input, the value
 * currently in the URL (what queries should subscribe to), and the setter.
 *
 * `options.resetParams` names other URL params that a commit deletes in
 * the same write — the browse page passes `['page']` (backlog #144) so
 * changing a filter drops the pagination param and the list restarts at
 * page 0 instead of stranding the reader on a page the new result set
 * may not have. Callers that do not paginate simply omit the option.
 *
 * The input also re-syncs when the URL moves underneath it (browser
 * back/forward, or another component writing the param): without this
 * the box kept showing the old term while the list already followed
 * the new URL. The sync is skipped while a debounce timer is pending
 * so a sibling param commit cannot wipe text the user is still typing;
 * the pending commit then wins, as before.
 */
export function useDebouncedSearchParam(
  param = 'q',
  debounceMs = 300,
  options?: { resetParams?: string[] },
): [string, string, (value: string) => void] {
  const [searchParams, setSearchParams] = useSearchParams();
  const [input, setInput] = useState(() => searchParams.get(param) ?? '');
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(
    () => () => {
      if (timer.current !== null) {
        clearTimeout(timer.current);
      }
    },
    [],
  );

  // Re-sync the input when the URL moves underneath the hook (see the
  // javadoc). A commit of this hook's own value also passes through
  // here; it only normalises the input to the trimmed URL value.
  useEffect(() => {
    if (timer.current === null) {
      setInput(searchParams.get(param) ?? '');
    }
  }, [searchParams, param]);

  const update = (value: string) => {
    setInput(value);
    if (timer.current !== null) {
      clearTimeout(timer.current);
    }
    timer.current = setTimeout(() => {
      timer.current = null;
      const trimmed = value.trim();
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          if (trimmed === '') {
            next.delete(param);
          } else {
            next.set(param, trimmed);
          }
          for (const resetParam of options?.resetParams ?? []) {
            next.delete(resetParam);
          }
          return next;
        },
        { replace: true },
      );
    }, debounceMs);
  };

  const committed = (searchParams.get(param) ?? '').trim();

  return [input, committed, update];
}
