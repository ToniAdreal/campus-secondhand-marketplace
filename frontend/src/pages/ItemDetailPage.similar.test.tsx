import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { Item } from '../api/items';
import { useAuthStore } from '../store/useAuthStore';
import ItemDetailPage from './ItemDetailPage';

const getSpy = vi.spyOn(api, 'get');

const fakeItem: Item = {
  id: 7,
  title: 'Used bike',
  description: 'Rides fine',
  priceCents: 24000,
  status: 'AVAILABLE',
  sellerId: 3,
  sellerUsername: 'seller',
  categoryId: 2,
  categoryName: 'Bikes',
  photoUrl: null,
  photos: [],
};

const fakeSimilar: Item[] = [
  {
    id: 8,
    title: 'Near bike',
    description: 'Almost the same',
    priceCents: 24500,
    status: 'AVAILABLE',
    sellerId: 4,
    sellerUsername: 'other',
    categoryId: 2,
    categoryName: 'Bikes',
    photoUrl: null,
    photos: [],
  },
  {
    id: 9,
    title: 'Far bike',
    description: 'Same category, further price',
    priceCents: 30000,
    status: 'AVAILABLE',
    sellerId: 5,
    sellerUsername: 'third',
    categoryId: 2,
    categoryName: 'Bikes',
    photoUrl: null,
    photos: [],
  },
];

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function renderAt() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/items/7']}>
        <Routes>
          <Route path="/items/:id" element={<ItemDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ItemDetailPage similar listings (backlog #202)', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
    // Same harness as the price-history test: the one-shot item
    // response fires first; every other GET is routed by URL below.
    getSpy.mockImplementation((url: string) => {
      if (String(url).includes('/similar')) {
        return Promise.resolve(envelope(fakeSimilar));
      }
      return Promise.resolve(
        envelope({ content: [], totalElements: 0, totalPages: 0, number: 0 }),
      );
    });
    getSpy.mockResolvedValueOnce(envelope(fakeItem));
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('renders the rail in backend order with browse-card links', async () => {
    renderAt();

    const section = await screen.findByRole('region', { name: 'Similar listings' });
    const entries = within(section).getAllByRole('listitem');
    expect(entries).toHaveLength(2);
    // The rail keeps the backend's pinned order verbatim (nearest
    // price first) — the client never re-sorts.
    expect(entries[0].textContent).toContain('Near bike');
    expect(entries[0].textContent).toContain('¥245.00');
    expect(entries[1].textContent).toContain('Far bike');
    expect(entries[1].textContent).toContain('¥300.00');
    expect(within(section).getByRole('link', { name: 'Near bike' }).getAttribute('href'))
      .toBe('/items/8');
    expect(within(section).getByRole('link', { name: 'Far bike' }).getAttribute('href'))
      .toBe('/items/9');
  });

  it('hides the rail when no similar listings come back', async () => {
    getSpy.mockImplementation((url: string) => {
      if (String(url).includes('/similar')) {
        return Promise.resolve(envelope([]));
      }
      return Promise.resolve(
        envelope({ content: [], totalElements: 0, totalPages: 0, number: 0 }),
      );
    });
    renderAt();

    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    expect(screen.queryByRole('region', { name: 'Similar listings' })).toBeNull();
    expect(screen.queryByText('Similar listings')).toBeNull();
  });

  it('hides the rail when the similar read fails', async () => {
    getSpy.mockImplementation((url: string) => {
      if (String(url).includes('/similar')) {
        return Promise.reject(new Error('404'));
      }
      return Promise.resolve(
        envelope({ content: [], totalElements: 0, totalPages: 0, number: 0 }),
      );
    });
    renderAt();

    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    expect(screen.queryByText('Similar listings')).toBeNull();
  });
});
