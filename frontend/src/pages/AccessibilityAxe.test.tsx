import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import axe from 'axe-core';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import type { Item } from '../api/items';
import type { Notification } from '../api/notifications';
import { useAuthStore } from '../store/useAuthStore';
import LoginPage from './LoginPage';
import RegisterPage from './RegisterPage';
import HomePage from './HomePage';
import ItemDetailPage from './ItemDetailPage';
import CreateListingPage from './CreateListingPage';
import SettingsPage from './SettingsPage';
import NotificationsPage from './NotificationsPage';

/**
 * Automated accessibility checks (backlog #183) — axe-core over the
 * rendered markup of the key routes, wired into the existing
 * Vitest + RTL setup (no new runner). axe-core is a devDependency
 * imported only from this test file, so it can never reach the
 * shipped bundle (the #165 size budget guards the bundle either way).
 *
 * The gate is exactly the backlog's: zero violations at the
 * serious/critical impact levels, over the WCAG 2.0/2.1 A+AA rule
 * tags. No rule is disabled anywhere in this file — a violation
 * found here is fixed in the page, not silenced. One honest jsdom
 * limit: `color-contrast` cannot complete in jsdom (it needs a real
 * canvas/layout engine), so axe reports it as *incomplete*, never as
 * a violation or a pass — contrast remains un-audited by this suite,
 * exactly as the README testing section states. These checks cover
 * rendered markup only: no keyboard walkthrough, no screen reader.
 */

const getSpy = vi.spyOn(api, 'get');

const buyer: AuthUser = { id: 5, username: 'buyer', email: 'buyer@example.com', roles: ['USER'] };

const fakeItem: Item = {
  id: 7,
  title: 'Used bike',
  description: 'Rides fine',
  priceCents: 12000,
  status: 'AVAILABLE',
  sellerId: 3,
  sellerUsername: 'seller',
  categoryId: 1,
  categoryName: 'Textbooks',
  photoUrl: null,
  photos: [],
};

const fakeNotification: Notification = {
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

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function emptyPage() {
  return { content: [], totalElements: 0, totalPages: 0, number: 0 };
}

/** One GET router for every page in the suite — unknown URLs fail, and
 * pages that call them must degrade gracefully (as Settings does). */
function routeGet(url: string) {
  if (url === '/items') {
    return Promise.resolve(
      envelope({ content: [fakeItem], totalElements: 1, totalPages: 1, number: 0 }),
    );
  }
  if (url === '/items/7') return Promise.resolve(envelope(fakeItem));
  if (url === '/categories') {
    return Promise.resolve(
      envelope([
        { id: 1, name: 'Textbooks', slug: 'textbooks' },
        { id: 2, name: 'Electronics', slug: 'electronics' },
      ]),
    );
  }
  if (url === '/notifications') {
    return Promise.resolve(
      envelope({ content: [fakeNotification], totalElements: 1, totalPages: 1, number: 0 }),
    );
  }
  if (url === '/notifications/unread-count') return Promise.resolve(envelope(1));
  if (url === '/users/me/saved-searches') return Promise.resolve(envelope([]));
  if (url === '/users/me/favorites') return Promise.resolve(envelope([]));
  if (url === '/users/me/recently-viewed') return Promise.resolve(envelope([]));
  if (url === '/messages') return Promise.resolve(envelope(emptyPage()));
  return Promise.reject(new Error(`no a11y fixture for GET ${url}`));
}

function renderRoute(path: string, routePath: string, element: React.ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path={routePath} element={element} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function expectNoSeriousOrCriticalViolations(container: HTMLElement) {
  const results = await axe.run(container, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
    resultTypes: ['violations'],
  });
  const blocking = results.violations.filter(
    (v) => v.impact === 'serious' || v.impact === 'critical',
  );
  expect(
    blocking.map(
      (v) => `${v.id} (${v.impact}): ${v.nodes.map((n) => n.target.join(' ')).join('; ')}`,
    ),
  ).toEqual([]);
}

describe('automated accessibility checks (axe-core, backlog #183)', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
    getSpy.mockImplementation((url: string) => routeGet(url) as never);
  });

  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  it('Login page has no serious/critical violations', async () => {
    const { container } = renderRoute('/login', '/login', <LoginPage />);
    expect(screen.getByLabelText(/username or email/i)).toBeTruthy();
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Register page has no serious/critical violations', async () => {
    const { container } = renderRoute('/register', '/register', <RegisterPage />);
    expect(screen.getByLabelText(/username/i)).toBeTruthy();
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Home / browse page has no serious/critical violations', async () => {
    const { container } = renderRoute('/', '/', <HomePage />);
    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Item detail page has no serious/critical violations', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/items/7', '/items/:id', <ItemDetailPage />);
    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Create listing page has no serious/critical violations', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/items/new', '/items/new', <CreateListingPage />);
    await waitFor(() => expect(screen.getByLabelText(/title/i)).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Settings page has no serious/critical violations', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/settings', '/settings', <SettingsPage />);
    expect(screen.getByLabelText('Current password')).toBeTruthy();
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Notifications page has no serious/critical violations', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/notifications', '/notifications', <NotificationsPage />);
    await waitFor(() => expect(screen.getByText(/order.*paid|paid/i)).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });
});
