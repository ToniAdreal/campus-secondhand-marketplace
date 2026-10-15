import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import NotificationsPage from './NotificationsPage';
import type { Notification } from '../api/notifications';
import type { Page } from '../api/items';
import type { AuthUser } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';

let mockPage: Page<Notification> | undefined;
let mockIsSuccess = true;
const markReadCalls: number[] = [];

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
  };
});

const buyer: AuthUser = { id: 5, username: 'buyer', email: 'buyer@example.com', roles: ['USER'] };

const unreadPaid: Notification = {
  id: 11,
  type: 'ORDER_PAID',
  orderId: 42,
  offerId: null,
  createdAt: '2026-10-13T08:00:00Z',
  readAt: null,
};

const readCompleted: Notification = {
  id: 12,
  type: 'ORDER_COMPLETED',
  orderId: 43,
  offerId: null,
  createdAt: '2026-10-13T07:00:00Z',
  readAt: '2026-10-13T07:30:00Z',
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
    mockIsSuccess = true;
    markReadCalls.length = 0;
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

  it('bounces anonymous visitors to /login', () => {
    useAuthStore.setState({ user: null });
    renderPage();

    expect(screen.queryByText('Notifications')).toBeNull();
    expect(screen.queryByText('No notifications yet.')).toBeNull();
  });
});
