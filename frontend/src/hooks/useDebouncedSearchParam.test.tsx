import { act, renderHook } from '@testing-library/react';
import { MemoryRouter, useSearchParams } from 'react-router-dom';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useDebouncedSearchParam } from './useDebouncedSearchParam';

// The external writer is a sibling inside the same MemoryRouter: it lets
// a test change the URL without going through the hook under test, which
// is how browser back/forward (or another component) moves the URL. It
// also exposes the live query string so tests can assert on the URL the
// hook actually wrote, not on a reconstructed one.
let externalSetSearchParams: ReturnType<typeof useSearchParams>[1] | null = null;
let currentSearch = '';

function wrapperFor() {
  return function Wrapper({ children }: { children: ReactNode }) {
    const [searchParams, setSearchParams] = useSearchParams();
    externalSetSearchParams = setSearchParams;
    currentSearch = searchParams.toString();
    return <>{children}</>;
  };
}

function routerWrapper(initialEntries: string[]) {
  const Inner = wrapperFor();
  return function RouterWrapper({ children }: { children: ReactNode }) {
    return (
      <MemoryRouter initialEntries={initialEntries}>
        <Inner>{children}</Inner>
      </MemoryRouter>
    );
  };
}

describe('useDebouncedSearchParam', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    externalSetSearchParams = null;
  });

  it('initialises input and committed from the URL', () => {
    const { result } = renderHook(() => useDebouncedSearchParam('q', 300), {
      wrapper: routerWrapper(['/?q=hello']),
    });
    expect(result.current[0]).toBe('hello');
    expect(result.current[1]).toBe('hello');
  });

  it('echoes input immediately while the param lags, then lands after the delay', () => {
    const { result } = renderHook(() => useDebouncedSearchParam('q', 300), {
      wrapper: routerWrapper(['/']),
    });

    act(() => {
      result.current[2]('hello');
    });
    expect(result.current[0]).toBe('hello');
    expect(result.current[1]).toBe('');

    act(() => {
      vi.advanceTimersByTime(299);
    });
    expect(result.current[1]).toBe('');

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(result.current[1]).toBe('hello');
  });

  it('trims the value written to the URL', () => {
    const { result } = renderHook(() => useDebouncedSearchParam('q', 300), {
      wrapper: routerWrapper(['/']),
    });

    act(() => {
      result.current[2]('  padded  ');
    });
    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(result.current[1]).toBe('padded');
  });

  it('rapid typing resets the timer so only the final value lands', () => {
    const { result } = renderHook(() => useDebouncedSearchParam('q', 300), {
      wrapper: routerWrapper(['/']),
    });

    act(() => {
      result.current[2]('h');
    });
    act(() => {
      vi.advanceTimersByTime(200);
    });
    expect(result.current[1]).toBe('');

    act(() => {
      result.current[2]('hello');
    });
    act(() => {
      vi.advanceTimersByTime(200);
    });
    // The first keystroke's timer must not have fired in the meantime.
    expect(result.current[1]).toBe('');

    act(() => {
      vi.advanceTimersByTime(100);
    });
    expect(result.current[1]).toBe('hello');
  });

  it('resetParams clears the named params on write and keeps unrelated params', () => {
    const { result } = renderHook(
      () => useDebouncedSearchParam('q', 300, { resetParams: ['page'] }),
      { wrapper: routerWrapper(['/?q=old&page=3&sort=newest']) },
    );

    act(() => {
      result.current[2]('fresh');
    });
    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(result.current[1]).toBe('fresh');

    // Assert on the URL the hook actually wrote: the page param is
    // gone, the unrelated sort param survives.
    const written = new URLSearchParams(currentSearch);
    expect(written.get('q')).toBe('fresh');
    expect(written.has('page')).toBe(false);
    expect(written.get('sort')).toBe('newest');
  });

  it('blank input clears the param instead of leaving ?q= behind', () => {
    const { result } = renderHook(() => useDebouncedSearchParam('q', 300), {
      wrapper: routerWrapper(['/?q=old']),
    });

    act(() => {
      result.current[2]('   ');
    });
    expect(result.current[0]).toBe('   ');
    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(result.current[1]).toBe('');
  });

  it('an external URL change re-syncs the input', () => {
    const { result } = renderHook(() => useDebouncedSearchParam('q', 300), {
      wrapper: routerWrapper(['/?q=old']),
    });
    expect(result.current[0]).toBe('old');

    act(() => {
      externalSetSearchParams?.({ q: 'external' });
    });
    expect(result.current[0]).toBe('external');
    expect(result.current[1]).toBe('external');
  });

  it('an external URL removal clears the input', () => {
    const { result } = renderHook(() => useDebouncedSearchParam('q', 300), {
      wrapper: routerWrapper(['/?q=old']),
    });

    act(() => {
      externalSetSearchParams?.({});
    });
    expect(result.current[0]).toBe('');
    expect(result.current[1]).toBe('');
  });
});
