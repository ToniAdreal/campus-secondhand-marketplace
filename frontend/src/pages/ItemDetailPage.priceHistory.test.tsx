import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { Item, PriceHistoryPoint } from '../api/items';
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
  categoryId: null,
  categoryName: null,
  photoUrl: null,
  photos: [],
};

const fakeHistory: PriceHistoryPoint[] = [
  { priceCents: 25000, changedAt: '2026-10-01T08:00:00Z' },
  { priceCents: 24000, changedAt: '2026-10-05T08:00:00Z' },
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

describe('ItemDetailPage price history (backlog #201)', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
    // The item query mounts before the price-history query (same
    // harness as ItemDetailPage.test.tsx): the one-shot item response
    // fires first; every other GET is routed by URL below.
    getSpy.mockImplementation((url: string) => {
      if (String(url).includes('price-history')) {
        return Promise.resolve(envelope(fakeHistory));
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

  it('renders the timeline oldest-first under the current price', async () => {
    renderAt();

    const section = await screen.findByRole('region', { name: 'Price history' });
    const entries = within(section).getAllByRole('listitem');
    expect(entries).toHaveLength(2);
    // Oldest first: the creation price precedes the later drop, and the
    // current price (¥240.00, also the page's headline price) is last.
    expect(entries[0].textContent).toContain('¥250.00');
    expect(entries[1].textContent).toContain('¥240.00');
    expect(entries[0].textContent).toContain(
      new Date('2026-10-01T08:00:00Z').toLocaleDateString(),
    );
  });

  it('hides the section when the timeline is empty', async () => {
    getSpy.mockImplementation((url: string) => {
      if (String(url).includes('price-history')) {
        return Promise.resolve(envelope([]));
      }
      return Promise.resolve(
        envelope({ content: [], totalElements: 0, totalPages: 0, number: 0 }),
      );
    });
    renderAt();

    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    expect(screen.queryByRole('region', { name: 'Price history' })).toBeNull();
    expect(screen.queryByText('Price history')).toBeNull();
  });

  it('hides the section when the history read fails (withdrawn listing 404)', async () => {
    getSpy.mockImplementation((url: string) => {
      if (String(url).includes('price-history')) {
        return Promise.reject(new Error('404'));
      }
      return Promise.resolve(
        envelope({ content: [], totalElements: 0, totalPages: 0, number: 0 }),
      );
    });
    renderAt();

    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    expect(screen.queryByText('Price history')).toBeNull();
  });
});
