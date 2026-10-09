import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { api } from './client';
import { itemKeys, type CreateListingInput, type Item, type Page, useCreateListing } from './items';
import { type Order, useCreateOrder } from './orders';

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

const fakePage: Page<Item> = { content: [fakeItem], totalElements: 1, totalPages: 1, number: 0 };

const fakeOrder: Order = {
  id: 9,
  itemId: 7,
  buyerId: 5,
  status: 'PENDING',
  amountCents: 12000,
  createdAt: '2026-10-06T17:00:00Z',
};

function ok(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

interface Harness {
  queryClient: QueryClient;
  invalidateSpy: { mock: { calls: unknown[][] } };
  setDataSpy: { mock: { calls: unknown[][] } };
}

function makeHarness(): Harness {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  // Seed the cache exactly the way the list/detail views populate it.
  queryClient.setQueryData(itemKeys.list(0, ''), fakePage);
  queryClient.setQueryData(itemKeys.list(0, 'bike'), fakePage);
  queryClient.setQueryData(itemKeys.detail(7), fakeItem);
  // Sanity: all three seeded queries live under the single `items` root.
  expect(queryClient.getQueryCache().findAll({ queryKey: itemKeys.all })).toHaveLength(3);

  const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
  const setDataSpy = vi.spyOn(queryClient, 'setQueryData');
  return { queryClient, invalidateSpy, setDataSpy };
}

function ListingProbe({ input }: { input: CreateListingInput }) {
  const { mutateAsync } = useCreateListing();
  return <button onClick={() => void mutateAsync(input).catch(() => {})}>create listing</button>;
}

function OrderProbe({ itemId }: { itemId: number }) {
  const { mutateAsync } = useCreateOrder();
  return <button onClick={() => void mutateAsync(itemId).catch(() => {})}>buy</button>;
}

const postSpy = vi.spyOn(api, 'post');

describe('mutation cache invalidation', () => {
  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
  });

  it('useCreateListing posts the payload and invalidates the whole items subtree', async () => {
    const { queryClient, invalidateSpy, setDataSpy } = makeHarness();
    postSpy.mockResolvedValueOnce(ok(fakeItem));
    const input: CreateListingInput = {
      title: 'Used bike',
      description: 'Rides fine',
      priceCents: 12000,
      categoryId: null,
    };

    render(
      <QueryClientProvider client={queryClient}>
        <ListingProbe input={input} />
      </QueryClientProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'create listing' }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(1));
    expect(postSpy).toHaveBeenCalledWith('/items', input);

    // Invalidated once, at the shared root — list pages (any page/query) AND
    // detail views all refetch from the server. No manual cache edits.
    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledTimes(1));
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: itemKeys.all });
    expect(setDataSpy).not.toHaveBeenCalled();
  });

  it('useCreateOrder sends an Idempotency-Key and invalidates the items subtree', async () => {
    const { queryClient, invalidateSpy, setDataSpy } = makeHarness();
    postSpy.mockResolvedValueOnce(ok(fakeOrder));

    render(
      <QueryClientProvider client={queryClient}>
        <OrderProbe itemId={7} />
      </QueryClientProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'buy' }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(1));
    expect(postSpy).toHaveBeenCalledWith(
      '/orders',
      { itemId: 7 },
      expect.objectContaining({
        headers: expect.objectContaining({ 'Idempotency-Key': expect.any(String) }),
      }),
    );

    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledTimes(1));
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: itemKeys.all });
    expect(setDataSpy).not.toHaveBeenCalled();
  });

  it('a failed mutation does not invalidate anything', async () => {
    const { queryClient, invalidateSpy } = makeHarness();
    postSpy.mockRejectedValueOnce(new Error('network down'));

    render(
      <QueryClientProvider client={queryClient}>
        <OrderProbe itemId={7} />
      </QueryClientProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'buy' }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(1));
    // give the rejected mutation a tick to (not) invalidate
    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(1));
    expect(invalidateSpy).not.toHaveBeenCalled();
  });
});
