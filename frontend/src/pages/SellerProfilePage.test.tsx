import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import SellerProfilePage from './SellerProfilePage';
import { useAuthStore } from '../store/useAuthStore';
import type { SellerProfile } from '../api/sellers';
import type { SellerReview } from '../api/reviews';
import type { Item, Page } from '../api/items';

let mockProfile: SellerProfile | undefined;
let mockProfileError = false;
let mockListings: Page<Item> | undefined;
let mockReviews: Page<SellerReview> | undefined;
let mockRespondMutate: (input: { reviewId: number; response: string }) => void = () => {};

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
    // The response form reads this hook; stub the mutation so the
    // page renders without a query client, and let #246 tests swap
    // in a capturing mutate.
    useRespondToReview: () => ({
      mutate: (input: { reviewId: number; response: string }) =>
        mockRespondMutate(input),
      isLoading: false,
      isError: false,
      error: null,
    }),
  };
});

// The block action reads the blocks hooks; stub them out (no blocks,
// no-op mutations) so the page renders without a query client.
vi.mock('../api/blocks', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/blocks')>();
  return {
    ...original,
    useMyBlocks: () => ({ data: [] }),
    useBlockUser: () => ({ mutate: () => {}, isLoading: false }),
    useUnblockUser: () => ({ mutate: () => {}, isLoading: false }),
  };
});

// The follow action reads the follows hooks; stub them out (a mutable
// follows list, no-op mutations) so the page renders without a query
// client.
let mockFollows: { sellerId: number }[] = [];

vi.mock('../api/follows', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/follows')>();
  return {
    ...original,
    useMyFollows: () => ({ data: mockFollows }),
    useFollowSeller: () => ({ mutate: () => {}, isLoading: false }),
    useUnfollowSeller: () => ({ mutate: () => {}, isLoading: false }),
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
  sellerResponse: null,
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

describe('SellerProfilePage follow control (#217)', () => {
  afterEach(() => {
    cleanup();
    mockProfile = undefined;
    mockProfileError = false;
    mockListings = undefined;
    mockReviews = undefined;
    mockFollows = [];
    useAuthStore.setState({ user: null });
  });

  it('renders the Follow button when the signed-in visitor is not following', () => {
    useAuthStore.setState({
      user: { id: 5, username: 'buyer', email: 'b@x.com', roles: ['USER'] },
    });
    mockProfile = profile;
    mockListings = { content: [], totalElements: 0, totalPages: 0, number: 0 };

    renderPage();

    expect(screen.getByRole('button', { name: 'Follow seller' })).toBeTruthy();
  });

  it('renders the Following (unfollow) state when already followed', () => {
    useAuthStore.setState({
      user: { id: 5, username: 'buyer', email: 'b@x.com', roles: ['USER'] },
    });
    mockProfile = profile;
    mockListings = { content: [], totalElements: 0, totalPages: 0, number: 0 };
    mockFollows = [{ sellerId: 3 }];

    renderPage();

    expect(screen.getByRole('button', { name: 'Unfollow seller' })).toBeTruthy();
  });

  it('hides the follow control for anonymous visitors and for the seller themself', () => {
    mockProfile = profile;
    mockListings = { content: [], totalElements: 0, totalPages: 0, number: 0 };

    // Anonymous.
    renderPage();
    expect(screen.queryByRole('button', { name: /[Ff]ollow seller/ })).toBeNull();
    cleanup();

    // Own profile.
    useAuthStore.setState({
      user: { id: 3, username: 'seller', email: 's@x.com', roles: ['USER'] },
    });
    renderPage();
    expect(screen.queryByRole('button', { name: /[Ff]ollow seller/ })).toBeNull();
  });
});

describe('SellerProfilePage seller responses (#246)', () => {
  afterEach(() => {
    cleanup();
    mockProfile = undefined;
    mockProfileError = false;
    mockListings = undefined;
    mockReviews = undefined;
    mockRespondMutate = () => {};
    useAuthStore.setState({ user: null });
  });

  it('renders the seller response under its review for every visitor', () => {
    useAuthStore.setState({ user: null });
    mockProfile = profile;
    mockListings = { content: [], totalElements: 0, totalPages: 0, number: 0 };
    mockReviews = {
      content: [{ ...review, sellerResponse: 'Thanks — happy trading!' }],
      totalElements: 1,
      totalPages: 1,
      number: 0,
    };

    renderPage();

    expect(screen.getByText("Seller's response")).toBeTruthy();
    expect(screen.getByText('Thanks — happy trading!')).toBeTruthy();
    // Anonymous visitors never see the form.
    expect(screen.queryByRole('button', { name: 'Respond' })).toBeNull();
  });

  it('shows the Respond form to the seller and submits the draft', () => {
    useAuthStore.setState({
      user: { id: 3, username: 'seller', email: 's@x.com', roles: ['USER'] },
    });
    mockProfile = profile;
    mockListings = { content: [], totalElements: 0, totalPages: 0, number: 0 };
    mockReviews = { content: [review], totalElements: 1, totalPages: 1, number: 0 };
    const submitted: { reviewId: number; response: string }[] = [];
    mockRespondMutate = (input) => {
      submitted.push(input);
    };

    renderPage();

    const box = screen.getByLabelText(/Seller response to buyer's review/);
    fireEvent.change(box, { target: { value: 'Thanks for the review!' } });
    fireEvent.click(screen.getByRole('button', { name: 'Respond' }));

    expect(submitted).toEqual([{ reviewId: 21, response: 'Thanks for the review!' }]);
  });

  it('shows the edit form prefilled when a response already exists', () => {
    useAuthStore.setState({
      user: { id: 3, username: 'seller', email: 's@x.com', roles: ['USER'] },
    });
    mockProfile = profile;
    mockListings = { content: [], totalElements: 0, totalPages: 0, number: 0 };
    mockReviews = {
      content: [{ ...review, sellerResponse: 'Old reply' }],
      totalElements: 1,
      totalPages: 1,
      number: 0,
    };

    renderPage();

    const box = screen.getByLabelText(
      /Seller response to buyer's review/,
    ) as HTMLTextAreaElement;
    expect(box.value).toBe('Old reply');
    expect(screen.getByRole('button', { name: 'Save response' })).toBeTruthy();
  });

  it('hides the form from a signed-in visitor who is not the seller', () => {
    useAuthStore.setState({
      user: { id: 5, username: 'buyer', email: 'b@x.com', roles: ['USER'] },
    });
    mockProfile = profile;
    mockListings = { content: [], totalElements: 0, totalPages: 0, number: 0 };
    mockReviews = { content: [review], totalElements: 1, totalPages: 1, number: 0 };

    renderPage();

    expect(screen.queryByLabelText(/Seller response/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'Respond' })).toBeNull();
  });
});
