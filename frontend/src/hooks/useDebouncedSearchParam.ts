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
 */
export function useDebouncedSearchParam(
  param = 'q',
  debounceMs = 300,
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
          return next;
        },
        { replace: true },
      );
    }, debounceMs);
  };

  const committed = (searchParams.get(param) ?? '').trim();

  return [input, committed, update];
}
