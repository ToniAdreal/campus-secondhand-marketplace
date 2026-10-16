import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import SellerProfilePage from './SellerProfilePage';
import type { SellerProfile } from '../api/sellers';
import type { SellerReview } from '../api/reviews';
import type { Item, Page } from '../api/items';

let mockProfile: SellerProfile | undefined;
let mockProfileError = false;
let mockListings: Page<Item> | undefined;
let mockReviews: Page<SellerReview> | undefined;

vi.mock('../api/sellers', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/sellers')>();
  return {
    ...original,
    useSellerProfile: () => ({
      data: mockProfile,
      isLoading: false,
      isError: mockProfileError,
    }),
    useSellerListings: () => ({
      data: mockListings,
      isLoading: false,
      isError: false,
    }),
  };
});

vi.mock('../api/reviews', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/reviews')>();
  return {
    ...original,
    useSellerReviews: () => ({
      data: mockReviews,
      isLoading: false,
      isError: false,
    }),
  };
});

// FavoriteButton reads the favorites hooks; stub them out so the card
// grid renders without a query client.
vi.mock('../api/favorites', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/favorites')>();
  return {
    ...original,
    useFavorites: () => ({ data: [] }),
    useToggleFavorite: () => ({ mutate: () => {}, isLoading: false }),
  };
});

const profile: SellerProfile = {
  username: 'seller',
  memberSince: '2026-09-01T00:00:00Z',
  availableListingCount: 1,
  completedSalesCount: 5,
  reviewCount: 2,
  averageRating: 4.5,
};

const liveItem: Item = {
  id: 7,
  title: 'Used bike',
  description: 'Rides fine',
  priceCents: 12000,
  status: 'AVAILABLE',
  sellerId: 3,
  sellerUsername: 'seller',
  categoryId: null,
  categoryName: null,
  photoUrl: null,
  photos: [],
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/seller/3']}>
      <Routes>
        <Route path="/seller/:id" element={<SellerProfilePage />} />
      </Routes>
    </MemoryRouter>,
  );
}

const review: SellerReview = {
  id: 21,
  orderId: 31,
  buyerId: 5,
  reviewerUsername: 'buyer',
  sellerId: 3,
  rating: 4,
  body: 'Fast handover, item as described',
  createdAt: '2026-10-05T00:00:00Z',
};

describe('SellerProfilePage (#174)', () => {
  afterEach(() => {
    cleanup();
    mockProfile = undefined;
    mockProfileError = false;
    mockListings = undefined;
    mockReviews = undefined;
  });

  it('renders the profile header and the live listings grid', () => {
    mockProfile = profile;
    mockListings = { content: [liveItem], totalElements: 1, totalPages: 1, number: 0 };

    renderPage();

    expect(screen.getByRole('heading', { name: 'seller' })).toBeTruthy();
    expect(screen.getByText(/5 completed sales/)).toBeTruthy();
    expect(screen.getByText(/1 live listings/)).toBeTruthy();
    expect(screen.getByText(/Member since/)).toBeTruthy();
    const card = screen.getByRole('link', { name: 'Used bike' });
    expect(card.getAttribute('href')).toBe('/items/7');
    expect(screen.getByText('¥120.00')).toBeTruthy();
  });

  it('renders the review aggregate and the review list (#175)', () => {
    mockProfile = profile;
    mockListings = { content: [], totalElements: 0, totalPages: 0, number: 0 };
    mockReviews = { content: [review], totalElements: 1, totalPages: 1, number: 0 };

    renderPage();

    expect(screen.getByText(/★ 4\.5 average · 2 reviews/)).toBeTruthy();
    expect(screen.getByText('Fast handover, item as described')).toBeTruthy();
    expect(screen.getByText(/buyer/)).toBeTruthy();
  });

  it('renders a no-reviews note when the seller has none (#175)', () => {
    mockProfile = { ...profile, reviewCount: 0, averageRating: null };
    mockListings = { content: [], totalElements: 0, totalPages: 0, number: 0 };
    mockReviews = { content: [], totalElements: 0, totalPages: 0, number: 0 };

    renderPage();

    expect(screen.getByText('No reviews yet')).toBeTruthy();
    expect(screen.getByText('No reviews yet.')).toBeTruthy();
  });

  it('renders an empty-storefront note when the seller has no live listings', () => {
    mockProfile = { ...profile, availableListingCount: 0 };
    mockListings = { content: [], totalElements: 0, totalPages: 0, number: 0 };

    renderPage();

    expect(screen.getByText('No live listings right now.')).toBeTruthy();
  });

  it('renders seller-not-found when the profile read fails (unknown/disabled 404)', () => {
    mockProfileError = true;

    renderPage();

    expect(screen.getByText('Seller not found.')).toBeTruthy();
  });
});
