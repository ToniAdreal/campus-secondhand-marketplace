import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import type { Item } from '../api/items';
import { type Order, useCreateOrder } from '../api/orders';
import { useAuthStore } from '../store/useAuthStore';
import ItemDetailPage from './ItemDetailPage';
import OrderConfirmationPage from './OrderConfirmationPage';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');
const patchSpy = vi.spyOn(api, 'patch');

const buyer: AuthUser = { id: 5, username: 'buyer', email: 'buyer@example.com', roles: ['USER'] };
const seller: AuthUser = { id: 3, username: 'seller', email: 'seller@example.com', roles: ['USER'] };
const admin: AuthUser = { id: 99, username: 'admin', email: 'admin@example.com', roles: ['ADMIN'] };

const fakeItem: Item = {
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
};

const fakeOrder: Order = {
  id: 9,
  itemId: 7,
  buyerId: 5,
  status: 'PENDING',
  amountCents: 12000,
  createdAt: '2026-10-06T17:00:00Z',
};

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function axiosFailure(status: number, message: string) {
  return {
    isAxiosError: true,
    response: { status, data: { code: status, message, data: null } },
  };
}

let currentPath = '';
function LocationProbe() {
  currentPath = useLocation().pathname;
  return null;
}

function renderAt(initialEntry: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <LocationProbe />
        <Routes>
          <Route path="/items/:id" element={<ItemDetailPage />} />
          <Route path="/orders/:id" element={<OrderConfirmationPage />} />
          <Route path="/login" element={<div>login probe</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ItemDetailPage buy-now', () => {
  beforeEach(() => {
    currentPath = '';
    // synchronous reset — store logout() now calls the server (would pollute spies)
    useAuthStore.setState({ user: null });
    setAccessToken(null);
    // Default route: the message-thread endpoint answers an empty page.
    // Tests pin their own GET with mockResolvedValueOnce, which fires first
    // (the item query mounts before the child thread query).
    getSpy.mockImplementation(() =>
      Promise.resolve(
        envelope({ content: [], totalElements: 0, totalPages: 0, number: 0 }),
      ),
    );
  });

  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
  });

  it('anonymous visitors get a login prompt, not a Buy button', async () => {
    getSpy.mockResolvedValueOnce(envelope(fakeItem));
    renderAt('/items/7');

    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    expect(screen.queryByRole('button', { name: /buy now/i })).toBeNull();
    expect(screen.getByRole('link', { name: 'Log in' }).getAttribute('href')).toBe('/login');
    // The detail DTO's sellerUsername is rendered as the seller identity.
    expect(screen.getByText('Sold by seller')).toBeTruthy();
    expect(getSpy).toHaveBeenCalledWith('/items/7');
  });

  it('a listing whose seller row is gone shows no Sold-by line', async () => {
    getSpy.mockResolvedValueOnce(envelope({ ...fakeItem, sellerUsername: null }));
    renderAt('/items/7');

    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    expect(screen.queryByText(/Sold by/)).toBeNull();
  });

  it('the seller does not see a Buy button on their own listing', async () => {
    useAuthStore.getState().login(seller, 'tok');
    getSpy.mockResolvedValueOnce(envelope(fakeItem));
    renderAt('/items/7');

    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    expect(screen.queryByRole('button', { name: /buy now/i })).toBeNull();
  });

  it('a non-AVAILABLE listing shows no Buy button even for a signed-in buyer', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    getSpy.mockResolvedValueOnce(envelope({ ...fakeItem, status: 'RESERVED' }));
    renderAt('/items/7');

    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    expect(screen.queryByRole('button', { name: /buy now/i })).toBeNull();
  });

  it('buy-now posts with an Idempotency-Key and navigates to the order confirmation', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    getSpy.mockResolvedValueOnce(envelope(fakeItem));
    // After the successful buy the page navigates to /orders/9, whose
    // confirmation view reads the order — route that fetch to the order and
    // keep the message thread on an empty thread, so the test never touches
    // the real network.
    getSpy.mockImplementation((url: string) =>
      Promise.resolve(
        envelope(
          url === '/orders/9'
            ? fakeOrder
            : { content: [], totalElements: 0, totalPages: 0, number: 0 },
        ),
      ),
    );
    postSpy.mockResolvedValueOnce(envelope(fakeOrder));
    renderAt('/items/7');

    fireEvent.click(await screen.findByRole('button', { name: /buy now/i }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(1));
    expect(postSpy).toHaveBeenCalledWith(
      '/orders',
      { itemId: 7 },
      expect.objectContaining({
        headers: expect.objectContaining({ 'Idempotency-Key': expect.any(String) }),
      }),
    );
    await waitFor(() => expect(currentPath).toBe('/orders/9'));
  });

  it('a 409 "already ordered" envelope maps to a friendly message', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    getSpy.mockResolvedValueOnce(envelope(fakeItem));
    postSpy.mockRejectedValueOnce(
      axiosFailure(409, 'item already has an active order'),
    );
    renderAt('/items/7');

    fireEvent.click(await screen.findByRole('button', { name: /buy now/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toBe(
      'Someone just grabbed this item — it is no longer available.',
    );
    // The raw envelope message never leaks to the UI.
    expect(screen.queryByText(/item already has an active order/)).toBeNull();
    // The user stays on the item page; no navigation on failure.
    expect(currentPath).toBe('/items/7');
  });

  it('each click sends a fresh Idempotency-Key (single key per click)', async () => {
    // Pin the hook directly: one click = one logical request = one fresh key.
    function OrderProbe({ itemId }: { itemId: number }) {
      const { mutate } = useCreateOrder();
      return <button onClick={() => mutate(itemId)}>buy</button>;
    }
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    postSpy.mockResolvedValue(envelope(fakeOrder));
    render(
      <QueryClientProvider client={queryClient}>
        <OrderProbe itemId={7} />
      </QueryClientProvider>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'buy' }));
    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: 'buy' }));
    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(2));

    const headers = (call: unknown[]) =>
      (call[2] as { headers: Record<string, string> }).headers['Idempotency-Key'];
    const key1 = headers(postSpy.mock.calls[0]);
    const key2 = headers(postSpy.mock.calls[1]);
    expect(key1).toMatch(/^[0-9a-f-]{36}$/);
    expect(key1).not.toBe(key2);
  });

  it('the order confirmation page shows the placed order', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    getSpy.mockResolvedValueOnce(envelope(fakeOrder));
    renderAt('/orders/9');

    await waitFor(() => expect(screen.getByText('Order confirmed')).toBeTruthy());
    expect(screen.getByText(/Order #9 is pending/)).toBeTruthy();
    expect(screen.getByText('¥120.00')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Back to the listing' }).getAttribute('href')).toBe(
      '/items/7',
    );
    expect(getSpy).toHaveBeenCalledWith('/orders/9');
  });
});

describe('ItemDetailPage mark-as-sold', () => {
  const emptyPage = { content: [], totalElements: 0, totalPages: 0, number: 0 };

  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
    // Route GETs by URL: the item query answers from itemResponse, the
    // message-thread query answers an empty page. Swapping itemResponse
    // before a mutation simulates the invalidated refetch after the server
    // flips the status.
    let itemResponse: Item = fakeItem;
    getSpy.mockImplementation((url: unknown) =>
      url === '/items/7'
        ? Promise.resolve(envelope(itemResponse))
        : Promise.resolve(envelope(emptyPage)),
    );
    (getSpy as unknown as { setItemResponse: (i: Item) => void }).setItemResponse = (i) => {
      itemResponse = i;
    };
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  function setItemResponse(item: Item) {
    (getSpy as unknown as { setItemResponse: (i: Item) => void }).setItemResponse(item);
  }

  it('the seller sees "Mark as sold" on an AVAILABLE listing; success patches {status:SOLD} and the page shows SOLD', async () => {
    useAuthStore.getState().login(seller, 'tok');
    const soldItem: Item = { ...fakeItem, status: 'SOLD' };
    patchSpy.mockResolvedValueOnce(envelope(soldItem));

    renderAt('/items/7');
    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());

    // the refetch after invalidation answers the SOLD listing
    setItemResponse(soldItem);
    fireEvent.click(screen.getByRole('button', { name: 'Mark as sold' }));

    await waitFor(() => expect(patchSpy).toHaveBeenCalledTimes(1));
    expect(patchSpy).toHaveBeenCalledWith('/items/7/status', { status: 'SOLD' });

    // success invalidates the items subtree -> useItem refetches, SOLD lands
    await waitFor(() => expect(screen.getByText('SOLD')).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Mark as sold' })).toBeNull();
  });

  it('the button shows on a RESERVED listing and disables while marking', async () => {
    useAuthStore.getState().login(seller, 'tok');
    setItemResponse({ ...fakeItem, status: 'RESERVED' });
    let resolvePatch!: (value: unknown) => void;
    patchSpy.mockImplementationOnce(() => new Promise((resolve) => { resolvePatch = resolve; }));

    renderAt('/items/7');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Mark as sold' })).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Mark as sold' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Marking…' })).toBeTruthy());
    expect((screen.getByRole('button', { name: 'Marking…' }) as HTMLButtonElement).disabled).toBe(
      true,
    );

    setItemResponse({ ...fakeItem, status: 'SOLD' });
    resolvePatch(envelope({ ...fakeItem, status: 'SOLD' }));
    await waitFor(() => expect(screen.getByText('SOLD')).toBeTruthy());
  });

  it('an ADMIN who is not the seller sees the button on their own-listing view', async () => {
    useAuthStore.getState().login(admin, 'tok');
    patchSpy.mockResolvedValue(envelope({ ...fakeItem, status: 'SOLD' }));

    renderAt('/items/7');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Mark as sold' })).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Mark as sold' }));
    await waitFor(() => expect(patchSpy).toHaveBeenCalledWith('/items/7/status', { status: 'SOLD' }));
  });

  it('a 422 (already SOLD) maps to a friendly message and the raw envelope message never leaks', async () => {
    useAuthStore.getState().login(seller, 'tok');
    patchSpy.mockRejectedValueOnce(axiosFailure(422, 'item is already SOLD'));

    renderAt('/items/7');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Mark as sold' })).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Mark as sold' }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toBe(
      'This listing is already sold — it can no longer be marked sold.',
    );
    expect(screen.queryByText(/item is already SOLD/)).toBeNull();
  });

  it('a non-owner sees no mark-sold button', async () => {
    useAuthStore.getState().login(buyer, 'tok');

    renderAt('/items/7');
    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Mark as sold' })).toBeNull();
    expect(patchSpy).not.toHaveBeenCalled();
  });

  it('anonymous visitors see no mark-sold button', async () => {
    renderAt('/items/7');
    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Mark as sold' })).toBeNull();
  });

  it('the seller sees no button once the listing is SOLD', async () => {
    useAuthStore.getState().login(seller, 'tok');
    setItemResponse({ ...fakeItem, status: 'SOLD' });

    renderAt('/items/7');
    await waitFor(() => expect(screen.getByText('Used bike')).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Mark as sold' })).toBeNull();
    expect(patchSpy).not.toHaveBeenCalled();
  });
});
