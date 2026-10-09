import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import HomePage from './HomePage';
import type { Page, Item } from '../api/items';

const calls: Array<{ page: number; q: string; categoryId: number | null }> = [];

vi.mock('../api/items', () => ({
  useItems: (page: number, q = '', categoryId: number | null = null) => {
    calls.push({ page, q, categoryId });
    const emptyPage: Page<Item> = { content: [], totalElements: 0, totalPages: 0, number: page };
    return { data: emptyPage, isLoading: false, isError: false };
  },
}));

vi.mock('../api/categories', () => ({
  useCategories: () => ({
    data: [
      { id: 1, name: 'Textbooks', slug: 'textbooks' },
      { id: 2, name: 'Electronics', slug: 'electronics' },
    ],
    isLoading: false,
    isError: false,
  }),
}));

let currentSearch = '';
function LocationProbe() {
  currentSearch = useLocation().search;
  return null;
}

function renderHome(initialEntry: string) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <LocationProbe />
      <HomePage />
    </MemoryRouter>,
  );
}

describe('HomePage debounced URL-synced search', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    calls.length = 0;
    currentSearch = '';
  });

  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it('hydrates the search box from ?q= on mount (refresh-safe, shareable)', () => {
    renderHome('/?q=bike');

    expect((screen.getByLabelText(/search listings/i) as HTMLInputElement).value).toBe('bike');
    // the committed query flows straight into the items query
    expect(calls[calls.length - 1]).toMatchObject({ q: 'bike' });
  });

  it('debounces typing into the URL and the items query', () => {
    renderHome('/');
    const input = screen.getByLabelText(/search listings/i);

    fireEvent.change(input, { target: { value: 'bike' } });
    fireEvent.change(input, { target: { value: 'bike helmet' } });

    // input updates immediately, but nothing is committed yet
    expect((input as HTMLInputElement).value).toBe('bike helmet');
    expect(currentSearch).toBe('');
    expect(calls[calls.length - 1]).toMatchObject({ q: '' });

    act(() => {
      vi.advanceTimersByTime(300);
    });

    expect(currentSearch).toBe('?q=bike+helmet');
    expect(calls[calls.length - 1]).toMatchObject({ q: 'bike helmet' });
  });

  it('trims the committed value and clears the param when emptied', () => {
    renderHome('/?q=bike');
    const input = screen.getByLabelText(/search listings/i);

    fireEvent.change(input, { target: { value: '   ' } });

    act(() => {
      vi.advanceTimersByTime(300);
    });

    expect(currentSearch).toBe('');
    expect(calls[calls.length - 1]).toMatchObject({ q: '' });
  });

  it('only commits the latest keystroke burst (intermediate timers cancelled)', () => {
    renderHome('/');
    const input = screen.getByLabelText(/search listings/i);

    fireEvent.change(input, { target: { value: 'a' } });
    act(() => {
      vi.advanceTimersByTime(200);
    });
    fireEvent.change(input, { target: { value: 'ab' } });
    act(() => {
      vi.advanceTimersByTime(300);
    });

    expect(currentSearch).toBe('?q=ab');
    // committed once, with the final value
    const committed = calls.map((c) => c.q);
    expect(committed[committed.length - 1]).toBe('ab');
    expect(committed).not.toContain('a');
  });
});

describe('HomePage category filter', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    calls.length = 0;
    currentSearch = '';
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it('hydrates the dropdown from ?categoryId= on mount (refresh-safe, shareable)', () => {
    renderHome('/?categoryId=2');

    expect((screen.getByLabelText(/filter by category/i) as HTMLSelectElement).value).toBe('2');
    expect(calls[calls.length - 1]).toMatchObject({ categoryId: 2 });
  });

  it('ignores a non-numeric ?categoryId= instead of sending it to the backend', () => {
    renderHome('/?categoryId=abc');

    expect((screen.getByLabelText(/filter by category/i) as HTMLSelectElement).value).toBe('');
    expect(calls[calls.length - 1]).toMatchObject({ categoryId: null });
  });

  it('selecting a category fires the request with the param and syncs the URL', () => {
    renderHome('/');
    const select = screen.getByLabelText(/filter by category/i);

    fireEvent.change(select, { target: { value: '1' } });

    expect(currentSearch).toBe('?categoryId=1');
    expect(calls[calls.length - 1]).toMatchObject({ categoryId: 1 });
  });

  it('choosing "All categories" clears the param and the filter', () => {
    renderHome('/?categoryId=1');
    const select = screen.getByLabelText(/filter by category/i);

    fireEvent.change(select, { target: { value: '' } });

    expect(currentSearch).toBe('');
    expect(calls[calls.length - 1]).toMatchObject({ categoryId: null });
  });

  it('combines with the search box: q and categoryId coexist in URL and request', () => {
    renderHome('/?q=bike');
    const select = screen.getByLabelText(/filter by category/i);

    fireEvent.change(select, { target: { value: '2' } });

    expect(currentSearch).toBe('?q=bike&categoryId=2');
    expect(calls[calls.length - 1]).toMatchObject({ q: 'bike', categoryId: 2 });

    // and typing a new search afterwards keeps the category filter
    const input = screen.getByLabelText(/search listings/i);
    fireEvent.change(input, { target: { value: 'lamp' } });
    act(() => {
      vi.advanceTimersByTime(300);
    });

    expect(currentSearch).toBe('?q=lamp&categoryId=2');
    expect(calls[calls.length - 1]).toMatchObject({ q: 'lamp', categoryId: 2 });
  });
});
