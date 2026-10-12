import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import MyListingsPage from './MyListingsPage';
import type { Item, Page } from '../api/items';
import type { AuthUser } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';

let mockPage: Page<Item> = { content: [], totalElements: 0, totalPages: 0, number: 0 };
const requestedPages: number[] = [];

vi.mock('../api/items', () => ({
  useMyListings: (page = 0) => {
    requestedPages.push(page);
    return {
      data: mockPage,
      isLoading: false,
      isError: false,
      isSuccess: true,
    };
  },
  listingPhotoUrls: (item: Item) => (item.photoUrl ? [item.photoUrl] : []),
}));

const seller: AuthUser = {
  id: 3,
  username: 'seller',
  email: 'seller@example.com',
  roles: ['USER'],
};

const soldLamp: Item = {
  id: 7,
  title: 'Desk lamp',
  description: 'Warm light',
  priceCents: 2500,
  status: 'SOLD',
  sellerId: 3,
  sellerUsername: 'seller',
  categoryId: null,
  categoryName: null,
  photoUrl: null,
  photos: [],
};

const reservedBike: Item = {
  id: 8,
  title: 'City bike',
  description: 'Rides fine',
  priceCents: 12000,
  status: 'RESERVED',
  sellerId: 3,
  sellerUsername: 'seller',
  categoryId: null,
  categoryName: null,
  photoUrl: null,
  photos: [],
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/my-listings']}>
      <MyListingsPage />
    </MemoryRouter>,
  );
}

describe('MyListingsPage (#146)', () => {
  beforeEach(() => {
    mockPage = { content: [], totalElements: 0, totalPages: 0, number: 0 };
    requestedPages.length = 0;
    useAuthStore.setState({ user: seller });
  });

  afterEach(() => {
    cleanup();
    useAuthStore.setState({ user: null });
    vi.clearAllMocks();
  });

  it('renders the seller listings with their real status badges', () => {
    mockPage = { content: [soldLamp, reservedBike], totalElements: 2, totalPages: 1, number: 0 };
    renderPage();

    expect(screen.getByText('Desk lamp')).toBeTruthy();
    expect(screen.getByText('SOLD')).toBeTruthy();
    expect(screen.getByText('City bike')).toBeTruthy();
    expect(screen.getByText('RESERVED')).toBeTruthy();
    expect(screen.getByText('¥25.00')).toBeTruthy();
    expect(requestedPages).toEqual([0]);
  });

  it('each card carries an edit link to the listing detail page', () => {
    mockPage = { content: [soldLamp], totalElements: 1, totalPages: 1, number: 0 };
    renderPage();

    const edit = screen.getByRole('link', { name: /edit listing/i });
    expect(edit.getAttribute('href')).toBe('/items/7');
  });

  it('shows the empty state when the seller has no listings', () => {
    renderPage();

    expect(screen.getByText(/haven't listed anything yet/i)).toBeTruthy();
    expect(screen.getByRole('link', { name: /sell an item/i }).getAttribute('href')).toBe(
      '/items/new',
    );
  });
});
