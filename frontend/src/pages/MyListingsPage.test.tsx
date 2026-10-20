import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import MyListingsPage from './MyListingsPage';
import type { Item, Page } from '../api/items';
import type { AuthUser } from '../api/auth';
import type { SellerReport } from '../api/appeals';
import { useAuthStore } from '../store/useAuthStore';

let mockPage: Page<Item> = { content: [], totalElements: 0, totalPages: 0, number: 0 };
const requestedPages: number[] = [];

const renewCalls: number[] = [];
const publishCalls: number[] = [];

let mockSellerReports: Page<SellerReport> = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  number: 0,
};
const appealCalls: { reportId: number; reason: string }[] = [];

vi.mock('../api/appeals', () => ({
  useSellerReports: () => ({
    data: mockSellerReports,
    isLoading: false,
    isError: false,
    isSuccess: true,
  }),
  useCreateAppeal: () => ({
    mutate: (
      input: { reportId: number; reason: string },
      opts?: { onError?: (e: unknown) => void; onSuccess?: () => void },
    ) => {
      appealCalls.push(input);
      // Mirror the server: a fresh appeal lands OPEN on the report.
      mockSellerReports = {
        ...mockSellerReports,
        content: mockSellerReports.content.map((r) =>
          r.id === input.reportId ? { ...r, appealId: 55, appealStatus: 'OPEN' as const } : r,
        ),
      };
      opts?.onSuccess?.();
    },
    isLoading: false,
  }),
  describeAppealError: () => 'appeal failed',
}));

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
  // Inline copies of the real helpers' semantics (the module is mocked):
  isListingExpired: (item: Item) =>
    item.status === 'EXPIRED' ||
    (item.status === 'AVAILABLE' &&
      item.expiresAt != null &&
      new Date(item.expiresAt).getTime() <= Date.now()),
  isListingExpiringSoon: (item: Item) =>
    item.status === 'AVAILABLE' &&
    item.expiresAt != null &&
    new Date(item.expiresAt).getTime() > Date.now() &&
    new Date(item.expiresAt).getTime() <= Date.now() + 7 * 24 * 60 * 60 * 1000,
  useRenewListing: () => ({
    mutate: (id: number) => {
      renewCalls.push(id);
    },
    isLoading: false,
  }),
  describeRenewError: () => 'renew failed',
  usePublishListing: () => ({
    mutate: (
      id: number,
      opts?: { onError?: (e: unknown) => void; onSuccess?: () => void },
    ) => {
      publishCalls.push(id);
      opts?.onSuccess?.();
    },
    isLoading: false,
  }),
  describePublishError: () => 'publish failed',
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

const expiredLamp: Item = {
  id: 9,
  title: 'Old lamp',
  description: 'Dusty',
  priceCents: 900,
  status: 'AVAILABLE',
  sellerId: 3,
  sellerUsername: 'seller',
  categoryId: null,
  categoryName: null,
  photoUrl: null,
  photos: [],
  expiresAt: '2020-01-01T00:00:00.000Z',
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
    mockSellerReports = { content: [], totalElements: 0, totalPages: 0, number: 0 };
    requestedPages.length = 0;
    renewCalls.length = 0;
    publishCalls.length = 0;
    appealCalls.length = 0;
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

  it('flags an expired listing in the seller view only, with its date and a renew button', () => {
    mockPage = { content: [expiredLamp], totalElements: 1, totalPages: 1, number: 0 };
    renderPage();

    expect(screen.getByText('Expired')).toBeTruthy();
    expect(screen.getByText(/expired on/i)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /renew listing/i }));
    expect(renewCalls).toEqual([9]);
  });

  it('shows the expiry date and no expired badge for a live listing', () => {
    const live: Item = {
      ...expiredLamp,
      id: 10,
      title: 'Fresh lamp',
      expiresAt: '2999-01-01T00:00:00.000Z',
    };
    mockPage = { content: [live], totalElements: 1, totalPages: 1, number: 0 };
    renderPage();

    expect(screen.queryByText('Expired')).toBeNull();
    expect(screen.getByText(/expires/i)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /renew listing/i })).toBeNull();
  });

  it('shows the view count on a row that carries one, and no views line without one (#186)', () => {
    const viewed: Item = { ...soldLamp, viewCount: 34 };
    mockPage = { content: [viewed, reservedBike], totalElements: 2, totalPages: 1, number: 0 };
    renderPage();

    expect(screen.getByText('34 views')).toBeTruthy();
    // reservedBike carries no viewCount (the backend omits the key on
    // surfaces that do not show it) — exactly one views line renders.
    expect(screen.getAllByText(/views$/)).toHaveLength(1);
  });

  it('uses the singular for exactly one view (#186)', () => {
    const viewedOnce: Item = { ...soldLamp, viewCount: 1 };
    mockPage = { content: [viewedOnce], totalElements: 1, totalPages: 1, number: 0 };
    renderPage();

    expect(screen.getByText('1 view')).toBeTruthy();
  });

  it('shows the empty state when the seller has no listings', () => {
    renderPage();

    expect(screen.getByText(/haven't listed anything yet/i)).toBeTruthy();
    expect(screen.getByRole('link', { name: /sell an item/i }).getAttribute('href')).toBe(
      '/items/new',
    );
  });

  const draftLamp: Item = {
    id: 12,
    title: 'Draft lamp',
    description: 'Not ready yet',
    priceCents: 1800,
    status: 'DRAFT',
    sellerId: 3,
    sellerUsername: 'seller',
    categoryId: null,
    categoryName: null,
    photoUrl: null,
    photos: [],
    expiresAt: null,
  };

  it('renders a draft with a Publish action that calls publish for its id (#198)', () => {
    mockPage = { content: [draftLamp, soldLamp], totalElements: 2, totalPages: 1, number: 0 };
    renderPage();

    expect(screen.getByText('Draft lamp')).toBeTruthy();
    expect(screen.getByText('DRAFT')).toBeTruthy();
    expect(screen.getByText(/visible only to you/i)).toBeTruthy();
    // Only the draft card offers Publish.
    const publishButtons = screen.getAllByRole('button', { name: /publish listing/i });
    expect(publishButtons).toHaveLength(1);
    fireEvent.click(publishButtons[0]);
    expect(publishCalls).toEqual([12]);
  });

  it('the drafts-only filter hides non-draft listings (#198)', () => {
    mockPage = { content: [draftLamp, soldLamp], totalElements: 2, totalPages: 1, number: 0 };
    renderPage();

    fireEvent.click(screen.getByLabelText(/show drafts only/i));
    expect(screen.getByText('Draft lamp')).toBeTruthy();
    expect(screen.queryByText('Desk lamp')).toBeNull();
  });

  it('no drafts section or publish action renders without drafts (#198)', () => {
    mockPage = { content: [soldLamp], totalElements: 1, totalPages: 1, number: 0 };
    renderPage();

    expect(screen.queryByText(/show drafts only/i)).toBeNull();
    expect(screen.queryByRole('button', { name: /publish listing/i })).toBeNull();
  });

  const withdrawnLamp: Item = {
    id: 11,
    title: 'Withdrawn lamp',
    description: 'Taken down by moderation',
    priceCents: 1500,
    status: 'WITHDRAWN',
    sellerId: 3,
    sellerUsername: 'seller',
    categoryId: null,
    categoryName: null,
    photoUrl: null,
    photos: [],
  };

  const actionedReport: SellerReport = {
    id: 21,
    itemId: 11,
    reason: 'SCAM',
    status: 'ACTIONED',
    createdAt: '2026-10-17T10:00:00Z',
    appealId: null,
    appealStatus: null,
    appealListingRestored: null,
  };

  it('a withdrawn-by-report listing offers an appeal and files it with the typed reason (#190)', () => {
    mockPage = { content: [withdrawnLamp], totalElements: 1, totalPages: 1, number: 0 };
    mockSellerReports = {
      content: [actionedReport],
      totalElements: 1,
      totalPages: 1,
      number: 0,
    };
    renderPage();

    expect(screen.getByText(/withdrawn after a report was actioned/i)).toBeTruthy();
    fireEvent.change(screen.getByLabelText(/appeal reason/i), {
      target: { value: 'this is my own textbook' },
    });
    fireEvent.click(screen.getByRole('button', { name: /appeal withdrawal/i }));
    expect(appealCalls).toEqual([{ reportId: 21, reason: 'this is my own textbook' }]);
  });

  it('an open appeal renders as pending and offers no second appeal form (#190)', () => {
    mockPage = { content: [withdrawnLamp], totalElements: 1, totalPages: 1, number: 0 };
    mockSellerReports = {
      content: [{ ...actionedReport, appealId: 55, appealStatus: 'OPEN' }],
      totalElements: 1,
      totalPages: 1,
      number: 0,
    };
    renderPage();

    expect(screen.getByText(/appeal pending/i)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /appeal withdrawal/i })).toBeNull();
  });

  it('a rejected appeal states that the withdrawal stands (#190)', () => {
    mockPage = { content: [withdrawnLamp], totalElements: 1, totalPages: 1, number: 0 };
    mockSellerReports = {
      content: [{ ...actionedReport, appealId: 55, appealStatus: 'REJECTED' }],
      totalElements: 1,
      totalPages: 1,
      number: 0,
    };
    renderPage();

    expect(screen.getByText(/appeal rejected — the withdrawal stands/i)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /appeal withdrawal/i })).toBeNull();
  });

  it('no appeal surface renders without an actioned report (#190)', () => {
    mockPage = { content: [soldLamp], totalElements: 1, totalPages: 1, number: 0 };
    mockSellerReports = {
      content: [{ ...actionedReport, itemId: 7, status: 'OPEN' }],
      totalElements: 1,
      totalPages: 1,
      number: 0,
    };
    renderPage();

    expect(screen.queryByText(/withdrawn after a report was actioned/i)).toBeNull();
    expect(screen.queryByRole('button', { name: /appeal withdrawal/i })).toBeNull();
  });
});
