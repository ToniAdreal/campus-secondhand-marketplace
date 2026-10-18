import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import NotificationsPage from './NotificationsPage';
import type { Notification } from '../api/notifications';
import type { SavedSearch } from '../api/savedSearches';
import type { Page } from '../api/items';
import type { AuthUser } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';

let mockPage: Page<Notification> | undefined;
let mockIsSuccess = true;
let mockUnreadCount = 0;
const markReadCalls: number[] = [];
const markAllReadCalls: number[] = [];
const deleteCalls: number[] = [];

vi.mock('../api/notifications', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/notifications')>();
  return {
    ...original,
    useNotifications: () => ({
      data: mockPage,
      isLoading: false,
      isError: false,
      isSuccess: mockIsSuccess,
    }),
    useMarkNotificationRead: () => ({
      mutate: (id: number) => {
        markReadCalls.push(id);
      },
      isLoading: false,
    }),
    useUnreadNotificationCount: () => ({ data: mockUnreadCount }),
    useMarkAllNotificationsRead: () => ({
      mutate: () => {
        markAllReadCalls.push(1);
      },
      isLoading: false,
    }),
    useDeleteNotification: () => ({
      mutate: (id: number) => {
        deleteCalls.push(id);
      },
      isLoading: false,
    }),
  };
});

let mockSavedSearches: SavedSearch[] = [];

vi.mock('../api/savedSearches', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/savedSearches')>();
  return {
    ...original,
    // Keep the real savedSearchBrowsePath — the link assertion below
    // pins the exact restored browse URL it builds (#182/#164).
    useSavedSearches: () => ({
      data: mockSavedSearches,
      isLoading: false,
      isError: false,
      isSuccess: true,
    }),
  };
});

const buyer: AuthUser = { id: 5, username: 'buyer', email: 'buyer@example.com', roles: ['USER'] };

const vintageSearch: SavedSearch = {
  id: 9,
  keyword: 'vintage',
  categoryId: null,
  minPriceCents: null,
  maxPriceCents: null,
  sort: 'newest',
  matchCount: 5,
  createdAt: '2026-10-13T05:00:00Z',
};

const unreadPaid: Notification = {
  id: 11,
  type: 'ORDER_PAID',
  orderId: 42,
  offerId: null,
  savedSearchId: null,
  matchedListingCount: null,
  itemId: null,
  oldPriceCents: null,
  newPriceCents: null,
  appealId: null,
  createdAt: '2026-10-13T08:00:00Z',
  readAt: null,
};

const newDeviceAlert: Notification = {
  id: 13,
  type: 'LOGIN_NEW_DEVICE',
  orderId: null,
  offerId: null,
  savedSearchId: null,
  matchedListingCount: null,
  itemId: null,
  oldPriceCents: null,
  newPriceCents: null,
  appealId: null,
  createdAt: '2026-10-13T06:00:00Z',
  readAt: null,
};

const readCompleted: Notification = {
  id: 12,
  type: 'ORDER_COMPLETED',
  orderId: 43,
  offerId: null,
  savedSearchId: null,
  matchedListingCount: null,
  itemId: null,
  oldPriceCents: null,
  newPriceCents: null,
  appealId: null,
  createdAt: '2026-10-13T07:00:00Z',
  readAt: '2026-10-13T07:30:00Z',
};

const priceDrop: Notification = {
  id: 15,
  type: 'FAVORITE_PRICE_DROP',
  orderId: null,
  offerId: null,
  savedSearchId: null,
  matchedListingCount: null,
  itemId: 77,
  oldPriceCents: 10000,
  newPriceCents: 8000,
  appealId: null,
  createdAt: '2026-10-13T10:00:00Z',
  readAt: null,
};

const savedSearchMatch: Notification = {
  id: 14,
  type: 'SAVED_SEARCH_MATCH',
  orderId: null,
  offerId: null,
  savedSearchId: 9,
  matchedListingCount: 5,
  itemId: null,
  oldPriceCents: null,
  newPriceCents: null,
  appealId: null,
  createdAt: '2026-10-13T09:00:00Z',
  readAt: null,
};

function pageOf(content: Notification[]): Page<Notification> {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    number: 0,
  };
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/notifications']}>
      <Routes>
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route path="/login" element={<div>login probe</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('NotificationsPage (#157)', () => {
  beforeEach(() => {
    mockPage = pageOf([]);
    mockSavedSearches = [];
    mockIsSuccess = true;
    markReadCalls.length = 0;
    markAllReadCalls.length = 0;
    deleteCalls.length = 0;
    mockUnreadCount = 0;
    useAuthStore.setState({ user: buyer });
  });

  afterEach(() => {
    cleanup();
    useAuthStore.setState({ user: null });
    vi.clearAllMocks();
  });

  it('renders rows with their type line and order link', () => {
    mockPage = pageOf([unreadPaid, readCompleted]);
    renderPage();

    expect(screen.getByText('An order for your listing was paid.')).toBeTruthy();
    expect(screen.getByText('Your order was completed.')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Order #42' }).getAttribute('href')).toBe('/orders/42');
    expect(screen.getByText('Read')).toBeTruthy();
  });

  it('a new-device alert links to sessions, never to an order (#178)', () => {
    mockPage = pageOf([newDeviceAlert]);
    renderPage();

    expect(screen.getByText(/New sign-in from a device or network/)).toBeTruthy();
    expect(
      screen
        .getByRole('link', { name: 'Review your sessions and login history' })
        .getAttribute('href'),
    ).toBe('/settings/sessions');
    expect(screen.queryByRole('link', { name: /Order #/ })).toBeNull();
  });

  it('a saved-search match says its batched count and links to the restored browse URL (#182)', () => {
    mockSavedSearches = [vintageSearch];
    mockPage = pageOf([savedSearchMatch]);
    renderPage();

    expect(screen.getByText('5 new listings match your saved search.')).toBeTruthy();
    expect(
      screen.getByRole('link', { name: 'View matching listings' }).getAttribute('href'),
    ).toBe('/?q=vintage');
    expect(screen.queryByRole('link', { name: /Order #/ })).toBeNull();
  });

  it('a price drop states both prices and links to the listing, never to an order (#189)', () => {
    mockPage = pageOf([priceDrop]);
    renderPage();

    expect(
      screen.getByText('A listing you saved dropped in price from ¥100.00 to ¥80.00.'),
    ).toBeTruthy();
    expect(screen.getByRole('link', { name: 'View listing' }).getAttribute('href')).toBe(
      '/items/77',
    );
    expect(screen.queryByRole('link', { name: /Order #/ })).toBeNull();
  });

  it('a saved-search match whose search was deleted falls back to the saved-searches list (#182)', () => {
    mockSavedSearches = [];
    mockPage = pageOf([savedSearchMatch]);
    renderPage();

    expect(
      screen.getByRole('link', { name: 'View saved searches' }).getAttribute('href'),
    ).toBe('/favorites');
  });

  it('shows the empty state when there are no notifications', () => {
    renderPage();

    expect(screen.getByText('No notifications yet.')).toBeTruthy();
  });

  it('an unread row\u2019s Mark read button posts that notification id', () => {
    mockPage = pageOf([unreadPaid]);
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Mark read' }));

    expect(markReadCalls).toEqual([11]);
  });

  it('Mark all read shows only while unread remain and fires the mutation (#173)', () => {
    mockPage = pageOf([unreadPaid]);
    mockUnreadCount = 1;
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Mark all read' }));
    expect(markAllReadCalls).toEqual([1]);
  });

  it('hides Mark all read when the unread count is 0 (#173)', () => {
    mockPage = pageOf([readCompleted]);
    mockUnreadCount = 0;
    renderPage();

    expect(screen.queryByRole('button', { name: 'Mark all read' })).toBeNull();
  });

  it('a row Delete control fires the delete mutation with that id (#173)', () => {
    mockPage = pageOf([unreadPaid]);
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Delete notification 11' }));
    expect(deleteCalls).toEqual([11]);
  });

  it('bounces anonymous visitors to /login', () => {
    useAuthStore.setState({ user: null });
    renderPage();

    expect(screen.queryByText('Notifications')).toBeNull();
    expect(screen.queryByText('No notifications yet.')).toBeNull();
  });
});
