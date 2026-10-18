import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import {
  describeAdminAppealsError,
  describeAppealError,
  useAdminAppeals,
  useCreateAppeal,
  useRejectAppeal,
  useSellerReports,
  useUpholdAppeal,
  type ReportAppeal,
} from './appeals';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function envelopeError(code: number, message: string) {
  return { response: { data: { code, message } } };
}

const fakeAppeal: ReportAppeal = {
  id: 3,
  reportId: 9,
  itemId: 7,
  sellerId: 5,
  reason: 'this is my own textbook',
  status: 'OPEN',
  listingRestored: null,
  createdAt: '2026-10-18T08:00:00Z',
  resolvedAt: null,
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

describe('describeAppealError', () => {
  it.each([
    ['already resolved', envelopeError(422, 'this report was already appealed and the appeal was resolved'), 'This report was already appealed and resolved.'],
    ['not actioned', envelopeError(422, 'only an actioned report can be appealed'), 'Only a report that was actioned can be appealed.'],
    ['bad reason', envelopeError(400, 'validation'), 'Please explain why the decision should be reconsidered (up to 500 characters).'],
    ['not seller', envelopeError(403, 'only the seller'), 'Only the seller of this listing can appeal its report.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in to appeal this report.'],
    ['unknown', envelopeError(404, 'report not found'), 'This report no longer exists.'],
    ['race', envelopeError(409, 'concurrent'), 'Someone just acted on this report — please refresh and try again.'],
    ['no envelope at all', new Error('boom'), 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeAppealError(error)).toBe(expected);
  });
});

describe('describeAdminAppealsError', () => {
  it.each([
    ['forbidden', envelopeError(403, 'forbidden'), 'You are not authorized to view the appeal queue.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in again to view the appeal queue.'],
    ['bad filter', envelopeError(400, 'bad'), 'That filter is not valid.'],
    ['unknown', envelopeError(404, 'appeal not found'), 'That appeal no longer exists.'],
    ['cross resolution', envelopeError(422, 'already resolved'), 'That appeal has already been resolved the other way.'],
    ['race', envelopeError(409, 'concurrent'), 'Someone just resolved this appeal — please refresh.'],
  ])('%s', (_label, error, expected) => {
    expect(describeAdminAppealsError(error)).toBe(expected);
  });
});

describe('appeal hooks', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('useCreateAppeal posts the reason to the nested seller route and unwraps the envelope', async () => {
    postSpy.mockResolvedValue(envelope(fakeAppeal));
    const client = makeClient();
    const { result } = renderHook(() => useCreateAppeal(), { wrapper: wrapperFor(client) });

    result.current.mutate({ reportId: 9, reason: 'this is my own textbook' });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(postSpy).toHaveBeenCalledWith('/seller/reports/9/appeal', {
      reason: 'this is my own textbook',
    });
    expect(result.current.data).toEqual(fakeAppeal);
  });

  it('useSellerReports reads /seller/reports', async () => {
    const page = { content: [], totalElements: 0, totalPages: 0, number: 0 };
    getSpy.mockResolvedValue(envelope(page));
    const client = makeClient();
    const { result } = renderHook(() => useSellerReports(true), {
      wrapper: wrapperFor(client),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(getSpy).toHaveBeenCalledWith('/seller/reports', { params: { page: 0, size: 100 } });
    expect(result.current.data).toEqual(page);
  });

  it('useAdminAppeals reads /admin/appeals with page params and omits an unset status', async () => {
    const page = { content: [fakeAppeal], totalElements: 1, totalPages: 1, number: 0 };
    getSpy.mockResolvedValue(envelope(page));
    const client = makeClient();
    const { result } = renderHook(() => useAdminAppeals(0, '', true), {
      wrapper: wrapperFor(client),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(getSpy).toHaveBeenCalledWith('/admin/appeals', { params: { page: 0, size: 20 } });
    expect(result.current.data).toEqual(page);
  });

  it('useUpholdAppeal posts to the uphold route', async () => {
    const resolved = { ...fakeAppeal, status: 'UPHELD', listingRestored: true };
    postSpy.mockResolvedValue(envelope(resolved));
    const client = makeClient();
    const { result } = renderHook(() => useUpholdAppeal(), { wrapper: wrapperFor(client) });

    result.current.mutate(3);

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(postSpy).toHaveBeenCalledWith('/admin/appeals/3/uphold', {});
    expect(result.current.data).toEqual(resolved);
  });

  it('useRejectAppeal posts to the reject route', async () => {
    const resolved = { ...fakeAppeal, status: 'REJECTED' };
    postSpy.mockResolvedValue(envelope(resolved));
    const client = makeClient();
    const { result } = renderHook(() => useRejectAppeal(), { wrapper: wrapperFor(client) });

    result.current.mutate(3);

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(postSpy).toHaveBeenCalledWith('/admin/appeals/3/reject', {});
    expect(result.current.data).toEqual(resolved);
  });
});
