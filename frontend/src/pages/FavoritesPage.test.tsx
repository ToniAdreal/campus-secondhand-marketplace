import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import FavoritesPage from './FavoritesPage';
import type { Item } from '../api/items';
import type { AuthUser } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';

let mockFavorites: Item[] = [];
const toggleCalls: Array<{ itemId: number; saved: boolean }> = [];

vi.mock('../api/favorites', () => ({
  useFavorites: () => ({
    data: mockFavorites,
    isLoading: false,
    isError: false,
    isSuccess: true,
  }),
  useToggleFavorite: () => ({
    mutate: (input: { itemId: number; saved: boolean }) => {
      toggleCalls.push(input);
    },
    isLoading: false,
  }),
}));

const buyer: AuthUser = { id: 5, username: 'buyer', email: 'buyer@example.com', roles: ['USER'] };

const savedBike: Item = {
  id: 7,
  title: 'Used bike',
  description: 'Rides fine',
  priceCents: 12000,
  status: 'SOLD',
  sellerId: 3,
  sellerUsername: 'seller',
  categoryId: null,
  categoryName: null,
  photoUrl: null,
  photos: [],
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/favorites']}>
      <FavoritesPage />
    </MemoryRouter>,
  );
}

describe('FavoritesPage (#143)', () => {
  beforeEach(() => {
    mockFavorites = [];
    toggleCalls.length = 0;
    useAuthStore.setState({ user: buyer });
  });

  afterEach(() => {
    cleanup();
    useAuthStore.setState({ user: null });
    vi.clearAllMocks();
  });

  it('renders saved listings with their real status badge', () => {
    mockFavorites = [savedBike];
    renderPage();

    expect(screen.getByText('Used bike')).toBeTruthy();
    expect(screen.getByText('SOLD')).toBeTruthy();
    expect(screen.getByText('¥120.00')).toBeTruthy();
  });

  it('shows the empty state when nothing is saved', () => {
    renderPage();

    expect(screen.getByText(/haven't saved any listings/i)).toBeTruthy();
  });

  it('the heart on a saved card unsaves it', () => {
    mockFavorites = [savedBike];
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: /remove from favorites/i }));

    expect(toggleCalls).toEqual([{ itemId: 7, saved: true }]);
  });
});
