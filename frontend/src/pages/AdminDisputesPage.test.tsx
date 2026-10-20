import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import type { OrderDispute } from '../api/disputes';
import { useAuthStore } from '../store/useAuthStore';
import AdminDisputesPage from './AdminDisputesPage';

const getSpy = vi.spyOn(api, 'get');
const postSpy = vi.spyOn(api, 'post');

const adminCaller: AuthUser = {
  id: 1,
  username: 'adminboss',
  email: 'admin@example.com',
  roles: ['ADMIN', 'USER'],
};

const openDispute: OrderDispute = {
  id: 10,
  orderId: 9,
  buyerId: 5,
  sellerId: 3,
  reason: 'the parcel never arrived',
  status: 'OPEN',
  previousStatus: 'PAID',
  listingRestored: null,
  createdAt: '2026-10-14T10:00:00Z',
  resolvedAt: null,
};
const refundedDispute: OrderDispute = {
  id: 8,
  orderId: 7,
  buyerId: 6,
  sellerId: 3,
  reason: 'shutter broken',
  status: 'RESOLVED_REFUND',
  previousStatus: 'COMPLETED',
  listingRestored: false,
  createdAt: '2026-10-12T10:00:00Z',
  resolvedAt: '2026-10-13T10:00:00Z',
};

function pageEnvelope(content: OrderDispute[], number: number, totalPages: number) {
  return {
    data: {
      code: 0,
      message: 'ok',
      data: { content, totalElements: content.length, totalPages, number },
    },
  } as never;
}

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

/**
 * Routes GETs by URL (backlog #200): each queue row also mounts an
 * evidence panel that reads /disputes/{id}/evidence — it gets an
 * empty list, everything else gets the disputes page.
 */
function mockGets(page: ReturnType<typeof pageEnvelope>) {
  getSpy.mockImplementation(((url: string) =>
    Promise.resolve(url.includes('/evidence') ? envelope([]) : page)) as never);
}

function renderAt(initialEntry: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/admin/disputes" element={<AdminDisputesPage />} />
          <Route path="/" element={<div>home page stub</div>} />
          <Route path="/orders/:id" element={<div>order detail stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminDisputesPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('renders dispute rows from GET /admin/disputes with resolution state', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    mockGets(pageEnvelope([openDispute, refundedDispute], 0, 1));

    renderAt('/admin/disputes');
    await waitFor(() => expect(screen.getByText('the parcel never arrived')).toBeTruthy());

    expect(screen.getByRole('link', { name: 'Order #9' }).getAttribute('href')).toBe('/orders/9');
    expect(screen.getByText('User #5')).toBeTruthy();
    expect(screen.getByRole('cell', { name: 'OPEN' })).toBeTruthy();
    expect(screen.getByRole('cell', { name: 'RESOLVED_REFUND' })).toBeTruthy();
    // The refunded COMPLETED dispute records that its listing stayed SOLD.
    expect(screen.getByText('Stayed SOLD')).toBeTruthy();
    // Resolution buttons render only on the OPEN row.
    expect(screen.getByRole('button', { name: 'Resolve with refund' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Release order' })).toBeTruthy();
    expect(getSpy).toHaveBeenCalledWith('/admin/disputes', {
      params: { page: 0, size: 20 },
    });
  });

  it('non-admin sessions bounce home and fire no request', async () => {
    useAuthStore.getState().login(
      { id: 9, username: 'plainuser', email: 'plain@example.com', roles: ['USER'] },
      'tok',
    );

    renderAt('/admin/disputes');
    await waitFor(() => expect(screen.getByText('home page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('resolving with refund posts to the resolve-refund route', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    mockGets(pageEnvelope([openDispute], 0, 1));
    postSpy.mockResolvedValue(
      envelope({ ...openDispute, status: 'RESOLVED_REFUND', listingRestored: true }),
    );

    renderAt('/admin/disputes');
    await waitFor(() => expect(screen.getByText('the parcel never arrived')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Resolve with refund' }));
    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith('/admin/disputes/10/resolve-refund', {}),
    );
  });

  it('empty queue renders the empty state', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    mockGets(pageEnvelope([], 0, 0));

    renderAt('/admin/disputes');
    await waitFor(() => expect(screen.getByText('No disputes yet.')).toBeTruthy());
  });
});
