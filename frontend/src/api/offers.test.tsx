import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import {
  describeOfferError,
  useAcceptOffer,
  useCreateOffer,
  useMadeOffers,
  type PriceOffer,
} from './offers';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function envelopeError(code: number, message: string) {
  return { response: { data: { code, message } } };
}

const fakeOffer: PriceOffer = {
  id: 4,
  itemId: 7,
  buyerId: 5,
  sellerId: 3,
  priceCents: 8000,
  status: 'PENDING',
  orderId: null,
  expiresAt: '2026-10-12T08:00:00Z',
  createdAt: '2026-10-10T08:00:00Z',
};

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function wrapperFor(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

describe('describeOfferError', () => {
  it.each([
    ['duplicate live offer', envelopeError(409, 'you already have a pending offer'), 'you already have a pending offer'],
    ['not-pending', envelopeError(422, 'offer is DECLINED'), 'offer is DECLINED'],
    ['unknown', envelopeError(404, 'offer not found'), 'That offer or listing no longer exists.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in to make an offer.'],
    ['bad price', envelopeError(400, 'validation'), 'Please enter a price above ¥0.'],
    ['no envelope at all', new Error('boom'), 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeOfferError(error)).toBe(expected);
  });
});

describe('offer hooks', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('useCreateOffer posts priceCents to the nested item route and unwraps the envelope', async () => {
    postSpy.mockResolvedValue(envelope(fakeOffer));
    const client = makeClient();
    const { result } = renderHook(() => useCreateOffer(), { wrapper: wrapperFor(client) });

    result.current.mutate({ itemId: 7, priceCents: 8000 });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(postSpy).toHaveBeenCalledWith('/items/7/offers', { priceCents: 8000 });
    expect(result.current.data).toEqual(fakeOffer);
  });

  it('useMadeOffers reads /offers/made and unwraps the page', async () => {
    const page = { content: [fakeOffer], totalElements: 1, totalPages: 1, number: 0 };
    getSpy.mockResolvedValue(envelope(page));
    const client = makeClient();
    const { result } = renderHook(() => useMadeOffers(), { wrapper: wrapperFor(client) });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(getSpy).toHaveBeenCalledWith('/offers/made');
    expect(result.current.data).toEqual(page);
  });

  it('useAcceptOffer posts to the accept route and invalidates offers + orders', async () => {
    postSpy.mockResolvedValue(envelope({ ...fakeOffer, status: 'ACCEPTED', orderId: 11 }));
    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');
    const { result } = renderHook(() => useAcceptOffer(), { wrapper: wrapperFor(client) });

    result.current.mutate(4);

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(postSpy).toHaveBeenCalledWith('/offers/4/accept', {});
    const keys = invalidateSpy.mock.calls.map((call) =>
      JSON.stringify((call[0] as { queryKey?: unknown } | undefined)?.queryKey),
    );
    expect(keys).toContain(JSON.stringify(['offers']));
    expect(keys).toContain(JSON.stringify(['orders']));
  });
});
