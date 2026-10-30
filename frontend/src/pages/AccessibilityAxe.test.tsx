import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import axe from 'axe-core';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import type { AdminDashboard, AdminUser } from '../api/admin';
import type { Item } from '../api/items';
import type { Message } from '../api/messages';
import type { Notification } from '../api/notifications';
import type { PriceOffer } from '../api/offers';
import type { Order } from '../api/orders';
import type { SellerPayout, SellerPayoutTotals } from '../api/payouts';
import type { ListingReport } from '../api/reports';
import type { SellerReview } from '../api/reviews';
import type { SavedSearch } from '../api/savedSearches';
import type { SellerProfile } from '../api/sellers';
import { useAuthStore } from '../store/useAuthStore';
import { useCartStore } from '../store/useCartStore';
import LoginPage from './LoginPage';
import RegisterPage from './RegisterPage';
import HomePage from './HomePage';
import ItemDetailPage from './ItemDetailPage';
import CreateListingPage from './CreateListingPage';
import SettingsPage from './SettingsPage';
import NotificationsPage from './NotificationsPage';
import CartPage from './CartPage';
import MyOrdersPage from './MyOrdersPage';
import SellerOrdersPage from './SellerOrdersPage';
import OffersPage from './OffersPage';
import AdminDashboardPage from './AdminDashboardPage';
import AdminUsersPage from './AdminUsersPage';
import AdminReportsPage from './AdminReportsPage';
import SellerProfilePage from './SellerProfilePage';
import FavoritesPage from './FavoritesPage';
import MessageSearchPage from './MessageSearchPage';
import OrderConfirmationPage from './OrderConfirmationPage';
import ForgotPasswordPage from './ForgotPasswordPage';
import ResetPasswordPage from './ResetPasswordPage';
import MyListingsPage from './MyListingsPage';
import SellerPayoutsPage from './SellerPayoutsPage';

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
 * found here is fixed in the page, not silenced. Backlog #278
 * extended the suite to the money path (Cart, My orders, Seller
 * orders, Offers) in both themes, on the same gate. Backlog #284
 * extends it to the third batch (admin, seller-profile and account
 * pages) in both themes, on the same gate. One honest jsdom
 * limit: `color-contrast` cannot complete in jsdom (it needs a real
 * canvas/layout engine), so axe reports it as *incomplete*, never as
 * a violation or a pass — contrast remains un-audited by this suite,
 * exactly as the README testing section states. These checks cover
 * rendered markup only: no keyboard walkthrough, no screen reader.
 */

const getSpy = vi.spyOn(api, 'get');

const buyer: AuthUser = { id: 5, username: 'buyer', email: 'buyer@example.com', roles: ['USER'] };

const admin: AuthUser = {
  id: 1,
  username: 'adminboss',
  email: 'admin@example.com',
  roles: ['ADMIN', 'USER'],
};

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

/**
 * Money-path fixtures (backlog #278): a PENDING row (no lifecycle
 * controls) plus a PAID row (dispute button + receipt button on the
 * buyer page, handover + ship forms on the seller page). No DISPUTED
 * row on purpose — it would mount the evidence panel and its
 * dispute/evidence GETs, which this suite does not fixture; the
 * dispute flow's own tests cover that surface.
 */
const fakeOrder: Order = {
  id: 9,
  itemId: 7,
  buyerId: 5,
  status: 'PENDING',
  amountCents: 12000,
  createdAt: '2026-10-06T17:00:00Z',
  captureId: null,
  refundId: null,
};

const fakePaidOrder: Order = {
  ...fakeOrder,
  id: 10,
  status: 'PAID',
  amountCents: 4500,
  captureId: 'cap_mock_aaaaaaaaaaaaaaaa',
};

const fakeMadeOffer: PriceOffer = {
  id: 21,
  itemId: 7,
  buyerId: 5,
  sellerId: 3,
  priceCents: 10000,
  counterPriceCents: null,
  status: 'PENDING',
  orderId: null,
  expiresAt: '2026-10-20T00:00:00Z',
  createdAt: '2026-10-13T08:00:00Z',
};

const fakeReceivedOffer: PriceOffer = {
  ...fakeMadeOffer,
  id: 22,
  buyerId: 4,
  priceCents: 9000,
};

const fakeSellerSummary = {
  totalOrders: 2,
  pendingCount: 1,
  paidCount: 1,
  completedCount: 0,
  cancelledCount: 0,
  refundedCount: 0,
  grossCents: 16500,
  currentMonth: { year: 2026, month: 10, totalOrders: 2, completedCount: 0, grossCents: 16500 },
};

/**
 * Third-batch fixtures (backlog #284). Populated, not empty, on
 * purpose: the axe run should see the real tables, cards, review
 * list, saved-search rows and payout ledger, not just the empty
 * states. The saved-search row is the SavedSearches surface itself:
 * there is no standalone SavedSearchesPage route (see the #284 note
 * on the Favorites test) — the saved-search list lives inside
 * FavoritesPage, so that test's populated fixture is what covers it.
 */
const fakeAdminDashboard: AdminDashboard = {
  window: '24h',
  windowStart: '2026-10-18T00:00:00Z',
  usersRegistered: 5,
  listingsCreated: 12,
  listingsAvailableNow: 11,
  listingsExpiredInWindow: 2,
  ordersTotalInWindow: 6,
  ordersPending: 1,
  ordersPaid: 2,
  ordersCompleted: 3,
  ordersCancelled: 4,
  ordersRefunded: 7,
  ordersDisputed: 8,
  captures: 9,
  refunds: 10,
  openReports: 13,
  openDisputes: 14,
  webhooksMatched: 15,
  webhooksMismatched: 16,
};

const fakeAdminUsers: AdminUser[] = [
  { id: 1, username: 'adminboss', email: 'admin@example.com', roles: ['ADMIN', 'USER'], disabled: false, totpEnabled: false, createdAt: '2026-09-01T00:00:00Z' },
  { id: 3, username: 'alice', email: 'alice@example.com', roles: ['USER'], disabled: false, totpEnabled: false, createdAt: '2026-09-02T00:00:00Z' },
  { id: 4, username: 'bob', email: 'bob@example.com', roles: ['USER'], disabled: true, totpEnabled: true, createdAt: '2026-09-03T00:00:00Z' },
];

const fakeReports: ListingReport[] = [
  { id: 10, reporterId: 5, itemId: 7, reason: 'SCAM', note: 'asks for payment off-platform', status: 'OPEN', createdAt: '2026-10-13T08:00:00Z' },
  { id: 11, reporterId: 4, itemId: 7, reason: 'SPAM', note: null, status: 'DISMISSED', createdAt: '2026-10-14T08:00:00Z' },
];

const fakeSellerProfile: SellerProfile = {
  username: 'seller',
  memberSince: '2026-09-01T00:00:00Z',
  availableListingCount: 1,
  completedSalesCount: 5,
  reviewCount: 2,
  averageRating: 4.5,
};

const fakeSellerReview: SellerReview = {
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

const fakeSavedSearch: SavedSearch = {
  id: 31,
  keyword: 'bike',
  categoryId: 1,
  minPriceCents: null,
  maxPriceCents: null,
  sort: 'newest',
  matchCount: 1,
  createdAt: '2026-10-13T08:00:00Z',
};

const fakeSearchedMessage: Message = {
  id: 41,
  itemId: 7,
  senderId: 5,
  receiverId: 3,
  body: 'does the blue bike lock come with a key',
  createdAt: '2026-10-13T08:00:00Z',
  readAt: null,
  editedAt: null,
  deletedAt: null,
  photoUrl: null,
};

const fakePayout: SellerPayout = {
  id: 51,
  orderId: 9,
  sellerId: 3,
  amountCents: 12000,
  adjustmentsCents: -10000,
  netCents: 2000,
  status: 'PAYABLE',
  createdAt: '2026-10-13T08:00:00Z',
  payableAt: '2026-10-20T08:00:00Z',
  paidAt: null,
};

const fakePayoutTotals: SellerPayoutTotals = {
  pendingCount: 0,
  pendingCents: 0,
  payableCount: 1,
  payableCents: 2000,
  paidCount: 0,
  paidCents: 0,
  adjustmentsCents: -10000,
  netCents: 2000,
};

function orderPage(orders: Order[]) {
  return { content: orders, totalElements: orders.length, totalPages: 1, number: 0 };
}

function offerPage(offers: PriceOffer[]) {
  return { content: offers, totalElements: offers.length, totalPages: 1, number: 0 };
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
  if (url === '/users/me/saved-searches') return Promise.resolve(envelope([fakeSavedSearch]));
  if (url === '/users/me/favorites') return Promise.resolve(envelope([fakeItem]));
  if (url === '/users/me/recently-viewed') return Promise.resolve(envelope([]));
  if (url === '/users/me/blocks') return Promise.resolve(envelope([]));
  if (url === '/users/me/follows') return Promise.resolve(envelope([]));
  if (url === '/orders') return Promise.resolve(envelope(orderPage([fakeOrder, fakePaidOrder])));
  if (url === '/orders/9') return Promise.resolve(envelope(fakeOrder));
  if (url === '/seller/orders')
    return Promise.resolve(envelope(orderPage([fakeOrder, fakePaidOrder])));
  if (url === '/seller/orders/summary') return Promise.resolve(envelope(fakeSellerSummary));
  if (url === '/seller/payouts')
    return Promise.resolve(
      envelope({ content: [fakePayout], totalElements: 1, totalPages: 1, number: 0 }),
    );
  if (url === '/seller/payouts/totals') return Promise.resolve(envelope(fakePayoutTotals));
  if (url === '/seller/reports') return Promise.resolve(envelope(emptyPage()));
  if (url === '/admin/dashboard') return Promise.resolve(envelope(fakeAdminDashboard));
  if (url === '/admin/users')
    return Promise.resolve(
      envelope({
        content: fakeAdminUsers,
        totalElements: fakeAdminUsers.length,
        totalPages: 1,
        number: 0,
      }),
    );
  if (url === '/admin/reports')
    return Promise.resolve(
      envelope({ content: fakeReports, totalElements: fakeReports.length, totalPages: 1, number: 0 }),
    );
  if (url === '/users/3/profile') return Promise.resolve(envelope(fakeSellerProfile));
  if (url === '/users/3/listings')
    return Promise.resolve(
      envelope({ content: [fakeItem], totalElements: 1, totalPages: 1, number: 0 }),
    );
  if (url === '/users/3/reviews')
    return Promise.resolve(
      envelope({ content: [fakeSellerReview], totalElements: 1, totalPages: 1, number: 0 }),
    );
  if (url === '/offers/made') return Promise.resolve(envelope(offerPage([fakeMadeOffer])));
  if (url === '/offers/received') return Promise.resolve(envelope(offerPage([fakeReceivedOffer])));
  if (url === '/messages') return Promise.resolve(envelope(emptyPage()));
  if (url === '/messages/search')
    return Promise.resolve(
      envelope({ content: [fakeSearchedMessage], totalElements: 1, totalPages: 1, number: 0 }),
    );
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
    // The cart tests seed the zustand cart; empty it so no other suite
    // in this file ever renders a leaked cart.
    useCartStore.setState({ items: [], sellerId: null, sellerUsername: null, batchId: null });
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

  it('Cart page has no serious/critical violations', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    // Seeded to match the /items/7 fixture (same title and price), so
    // the open-time revalidation keeps the row instead of rewriting it.
    useCartStore.setState({
      items: [{ itemId: 7, title: 'Used bike', priceCents: 12000, sellerId: 3, sellerUsername: 'seller' }],
      sellerId: 3,
      sellerUsername: 'seller',
      batchId: 'a11y-batch-1',
    });
    const { container } = renderRoute('/cart', '/cart', <CartPage />);
    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('My orders page has no serious/critical violations', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/orders', '/orders', <MyOrdersPage />);
    await waitFor(() => expect(screen.getByText('Order #9')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Seller orders page has no serious/critical violations', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/seller/orders', '/seller/orders', <SellerOrdersPage />);
    await waitFor(() => expect(screen.getByText('Order #9')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Offers page has no serious/critical violations', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/offers', '/offers', <OffersPage />);
    await waitFor(() => expect(screen.getAllByText(/offered/).length).toBe(2));
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Admin dashboard page has no serious/critical violations', async () => {
    useAuthStore.setState({ user: admin });
    setAccessToken('tok');
    const { container } = renderRoute('/admin/dashboard', '/admin/dashboard', <AdminDashboardPage />);
    await waitFor(() => expect(screen.getByText('Users registered')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Admin users page has no serious/critical violations', async () => {
    useAuthStore.setState({ user: admin });
    setAccessToken('tok');
    const { container } = renderRoute('/admin/users', '/admin/users', <AdminUsersPage />);
    await waitFor(() => expect(screen.getByText('alice')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Admin reports page has no serious/critical violations', async () => {
    useAuthStore.setState({ user: admin });
    setAccessToken('tok');
    const { container } = renderRoute('/admin/reports', '/admin/reports', <AdminReportsPage />);
    await waitFor(() => expect(screen.getByRole('cell', { name: 'SCAM' })).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Seller profile page has no serious/critical violations', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/seller/3', '/seller/:id', <SellerProfilePage />);
    await waitFor(() => expect(screen.getByRole('heading', { name: 'seller' })).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Favorites page has no serious/critical violations', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/favorites', '/favorites', <FavoritesPage />);
    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Saved searches section (inside Favorites) has no serious/critical violations', async () => {
    // No standalone SavedSearchesPage route exists in main.tsx: the
    // saved-search list renders inside FavoritesPage (see the #284
    // fixture note). This test pins that surface explicitly — the
    // populated fixture above renders its link row and delete button —
    // rather than inventing a route the app does not have.
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/favorites', '/favorites', <FavoritesPage />);
    await waitFor(() => expect(screen.getByText(/"bike"/)).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Message search page has no serious/critical violations', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/messages?q=bike', '/messages', <MessageSearchPage />);
    await waitFor(() =>
      expect(screen.getByText('does the blue bike lock come with a key')).toBeTruthy(),
    );
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Order confirmation page has no serious/critical violations', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/orders/9', '/orders/:id', <OrderConfirmationPage />);
    await waitFor(() => expect(screen.getByText('Order confirmed')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Forgot password page has no serious/critical violations', async () => {
    const { container } = renderRoute('/forgot-password', '/forgot-password', <ForgotPasswordPage />);
    expect(screen.getByLabelText(/username or email/i)).toBeTruthy();
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Reset password page has no serious/critical violations', async () => {
    const { container } = renderRoute(
      '/reset-password?token=a11y-token',
      '/reset-password',
      <ResetPasswordPage />,
    );
    expect(screen.getByLabelText('New password')).toBeTruthy();
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('My listings page has no serious/critical violations', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/my-listings', '/my-listings', <MyListingsPage />);
    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Seller payouts page has no serious/critical violations', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/seller/payouts', '/seller/payouts', <SellerPayoutsPage />);
    await waitFor(() => expect(screen.getByText('Payout for order #9')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });
});

/**
 * Same key pages under the dark theme (backlog #227). The `dark` class
 * on <html> is what flips the palette, so these tests render the pages
 * with the class applied and assert the same zero-serious/critical gate
 * — a dark-variant class that broke markup (e.g. a lost label) would
 * fail here, not just in the light suite. Honest jsdom limit, same as
 * #183: `color-contrast` still reports incomplete (no canvas/layout),
 * so real contrast in the dark palette is NOT audited by this suite —
 * the dark values were chosen as dark-bg/light-text pairs (see
 * OrderStatusChip and the shell) and need a visual pass, not an axe
 * claim. The class is removed after each test so no other suite sees
 * a leaked theme.
 */
describe('automated accessibility checks under the dark theme (backlog #227)', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
    getSpy.mockImplementation((url: string) => routeGet(url) as never);
    document.documentElement.classList.add('dark');
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    useAuthStore.setState({ user: null });
    setAccessToken(null);
    useCartStore.setState({ items: [], sellerId: null, sellerUsername: null, batchId: null });
    document.documentElement.classList.remove('dark');
  });

  it('Home / browse page has no serious/critical violations in dark mode', async () => {
    const { container } = renderRoute('/', '/', <HomePage />);
    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Item detail page has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/items/7', '/items/:id', <ItemDetailPage />);
    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Login page has no serious/critical violations in dark mode', async () => {
    const { container } = renderRoute('/login', '/login', <LoginPage />);
    expect(screen.getByLabelText(/username or email/i)).toBeTruthy();
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Register page has no serious/critical violations in dark mode', async () => {
    const { container } = renderRoute('/register', '/register', <RegisterPage />);
    expect(screen.getByLabelText(/username/i)).toBeTruthy();
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Create listing page has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/items/new', '/items/new', <CreateListingPage />);
    await waitFor(() => expect(screen.getByLabelText(/title/i)).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Notifications page has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/notifications', '/notifications', <NotificationsPage />);
    await waitFor(() => expect(screen.getByText(/order.*paid|paid/i)).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Settings page (with the theme control) has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/settings', '/settings', <SettingsPage />);
    expect(screen.getByLabelText('Current password')).toBeTruthy();
    expect(screen.getByRole('radio', { name: /dark/i })).toBeTruthy();
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Cart page has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    useCartStore.setState({
      items: [{ itemId: 7, title: 'Used bike', priceCents: 12000, sellerId: 3, sellerUsername: 'seller' }],
      sellerId: 3,
      sellerUsername: 'seller',
      batchId: 'a11y-batch-1',
    });
    const { container } = renderRoute('/cart', '/cart', <CartPage />);
    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('My orders page has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/orders', '/orders', <MyOrdersPage />);
    await waitFor(() => expect(screen.getByText('Order #9')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Seller orders page has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/seller/orders', '/seller/orders', <SellerOrdersPage />);
    await waitFor(() => expect(screen.getByText('Order #9')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Offers page has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/offers', '/offers', <OffersPage />);
    await waitFor(() => expect(screen.getAllByText(/offered/).length).toBe(2));
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Admin dashboard page has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: admin });
    setAccessToken('tok');
    const { container } = renderRoute('/admin/dashboard', '/admin/dashboard', <AdminDashboardPage />);
    await waitFor(() => expect(screen.getByText('Users registered')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Admin users page has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: admin });
    setAccessToken('tok');
    const { container } = renderRoute('/admin/users', '/admin/users', <AdminUsersPage />);
    await waitFor(() => expect(screen.getByText('alice')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Admin reports page has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: admin });
    setAccessToken('tok');
    const { container } = renderRoute('/admin/reports', '/admin/reports', <AdminReportsPage />);
    await waitFor(() => expect(screen.getByRole('cell', { name: 'SCAM' })).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Seller profile page has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/seller/3', '/seller/:id', <SellerProfilePage />);
    await waitFor(() => expect(screen.getByRole('heading', { name: 'seller' })).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Favorites page has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/favorites', '/favorites', <FavoritesPage />);
    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Saved searches section (inside Favorites) has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/favorites', '/favorites', <FavoritesPage />);
    await waitFor(() => expect(screen.getByText(/"bike"/)).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Message search page has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/messages?q=bike', '/messages', <MessageSearchPage />);
    await waitFor(() =>
      expect(screen.getByText('does the blue bike lock come with a key')).toBeTruthy(),
    );
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Order confirmation page has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/orders/9', '/orders/:id', <OrderConfirmationPage />);
    await waitFor(() => expect(screen.getByText('Order confirmed')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Forgot password page has no serious/critical violations in dark mode', async () => {
    const { container } = renderRoute('/forgot-password', '/forgot-password', <ForgotPasswordPage />);
    expect(screen.getByLabelText(/username or email/i)).toBeTruthy();
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Reset password page has no serious/critical violations in dark mode', async () => {
    const { container } = renderRoute(
      '/reset-password?token=a11y-token',
      '/reset-password',
      <ResetPasswordPage />,
    );
    expect(screen.getByLabelText('New password')).toBeTruthy();
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('My listings page has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/my-listings', '/my-listings', <MyListingsPage />);
    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });

  it('Seller payouts page has no serious/critical violations in dark mode', async () => {
    useAuthStore.setState({ user: buyer });
    setAccessToken('tok');
    const { container } = renderRoute('/seller/payouts', '/seller/payouts', <SellerPayoutsPage />);
    await waitFor(() => expect(screen.getByText('Payout for order #9')).toBeTruthy());
    await expectNoSeriousOrCriticalViolations(container);
  });
});
