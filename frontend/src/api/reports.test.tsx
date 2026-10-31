import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import type { Page } from './items';
import {
  adminReportKeys,
  describeAdminReportsError,
  describeReportError,
  REPORT_REASONS,
  REPORT_STATUSES,
  useActionReport,
  useAdminReports,
  useCreateReport,
  useDismissReport,
  type ListingReport,
} from './reports';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function envelopeError(code: number, message: string) {
  return { response: { data: { code, message } } };
}

const fakeReport: ListingReport = {
  id: 9,
  reporterId: 5,
  itemId: 7,
  reason: 'SCAM',
  note: 'Seller asks for off-platform payment.',
  status: 'OPEN',
  createdAt: '2026-10-12T08:00:00Z',
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

describe('report allowlists', () => {
  it('pins the backend ReportReason enum values', () => {
    expect(REPORT_REASONS).toEqual(['SPAM', 'SCAM', 'PROHIBITED', 'OTHER']);
  });

  it('pins the backend ReportStatus enum values', () => {
    expect(REPORT_STATUSES).toEqual(['OPEN', 'DISMISSED', 'ACTIONED']);
  });
});

describe('adminReportKeys', () => {
  it('hangs the list key off the admin reports subtree', () => {
    expect(adminReportKeys.all).toEqual(['admin', 'reports']);
    expect(adminReportKeys.list(0, '')).toEqual(['admin', 'reports', 'list', 0, '']);
    expect(adminReportKeys.list(2, 'OPEN')).toEqual(['admin', 'reports', 'list', 2, 'OPEN']);
  });

  it('varies with page and status so filtered pages never share a cache entry', () => {
    expect(adminReportKeys.list(0, '')).not.toEqual(adminReportKeys.list(1, ''));
    expect(adminReportKeys.list(0, '')).not.toEqual(adminReportKeys.list(0, 'OPEN'));
    expect(adminReportKeys.list(0, 'OPEN')).not.toEqual(adminReportKeys.list(0, 'DISMISSED'));
  });

  it('never collides with the sibling admin users subtree', () => {
    // The admin users subtree is ['admin', 'users', …] (api/admin.ts);
    // invalidating the reports subtree must not refetch the user list.
    expect(adminReportKeys.all).not.toEqual(['admin', 'users']);
    expect(adminReportKeys.list(0, '')[1]).not.toBe('users');
  });
});

describe('describeReportError', () => {
  it.each([
    ['own listing', envelopeError(422, 'own listing'), 'You cannot report your own listing.'],
    ['rate-limited', envelopeError(429, 'slow down'), 'You are reporting too quickly — please wait a moment and try again.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in to report this listing.'],
    ['unknown listing', envelopeError(404, 'not found'), 'This listing no longer exists.'],
    ['missing reason', envelopeError(400, 'validation'), 'Please choose a reason for the report.'],
    ['server message passthrough', envelopeError(500, 'upstream blew up'), 'upstream blew up'],
    ['no envelope at all', new Error('boom'), 'Something went wrong. Please try again.'],
    // The no-envelope case leaves `response` undefined, so the first `?.`
    // still guards both chains; only a present-but-dataless response
    // exercises the second `?.` in `response?.data?.code/message`
    // (Stryker 287: the four OptionalChaining survivors in this file).
    ['response without data', { response: {} }, 'Something went wrong. Please try again.'],
    ['response with empty data', { response: { data: {} } }, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeReportError(error)).toBe(expected);
  });
});

describe('describeAdminReportsError', () => {
  it.each([
    ['non-admin', envelopeError(403, 'forbidden'), 'You are not authorized to view the moderation queue.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in again to view the moderation queue.'],
    ['bad filter', envelopeError(400, 'validation'), 'That filter is not valid.'],
    ['unknown report', envelopeError(404, 'not found'), 'That report no longer exists.'],
    ['resolved the other way', envelopeError(422, 'already actioned'), 'That report has already been resolved the other way.'],
    ['server message passthrough', envelopeError(500, 'upstream blew up'), 'upstream blew up'],
    ['no envelope at all', new Error('boom'), 'Something went wrong. Please try again.'],
    // Same second-`?.` gap as describeReportError above: a present
    // response with no data is the only shape that distinguishes
    // `response?.data?.code/message` from its OptionalChaining mutants.
    ['response without data', { response: {} }, 'Something went wrong. Please try again.'],
    ['response with empty data', { response: { data: {} } }, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeAdminReportsError(error)).toBe(expected);
  });
});

describe('report hooks', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('useCreateReport posts reason only when no note is given and unwraps the envelope', async () => {
    postSpy.mockResolvedValue(envelope(fakeReport));
    const client = makeClient();
    const { result } = renderHook(() => useCreateReport(), { wrapper: wrapperFor(client) });

    result.current.mutate({ itemId: 7, reason: 'SCAM' });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(postSpy).toHaveBeenCalledWith('/items/7/report', { reason: 'SCAM' });
    expect(result.current.data).toEqual(fakeReport);
  });

  it('useCreateReport trims a supplied note before posting', async () => {
    postSpy.mockResolvedValue(envelope({ ...fakeReport, note: 'trimmed note' }));
    const client = makeClient();
    const { result } = renderHook(() => useCreateReport(), { wrapper: wrapperFor(client) });

    result.current.mutate({ itemId: 7, reason: 'SPAM', note: '  trimmed note  ' });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(postSpy).toHaveBeenCalledWith('/items/7/report', {
      reason: 'SPAM',
      note: 'trimmed note',
    });
  });

  it('useCreateReport drops a blank note instead of sending whitespace', async () => {
    postSpy.mockResolvedValue(envelope({ ...fakeReport, note: null }));
    const client = makeClient();
    const { result } = renderHook(() => useCreateReport(), { wrapper: wrapperFor(client) });

    result.current.mutate({ itemId: 7, reason: 'OTHER', note: '   ' });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(postSpy).toHaveBeenCalledWith('/items/7/report', { reason: 'OTHER' });
  });

  it('useAdminReports GETs /admin/reports paginated with no status param when unfiltered', async () => {
    const page: Page<ListingReport> = {
      content: [fakeReport],
      totalElements: 1,
      totalPages: 1,
      number: 0,
    };
    getSpy.mockResolvedValueOnce(envelope(page));
    const client = makeClient();
    const { result } = renderHook(() => useAdminReports(), { wrapper: wrapperFor(client) });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(getSpy).toHaveBeenCalledTimes(1);
    expect(getSpy).toHaveBeenCalledWith('/admin/reports', {
      params: { page: 0, size: 20 },
    });
    expect(result.current.data).toEqual(page);
  });

  it('useAdminReports sends the status filter and a caller-supplied size', async () => {
    const page: Page<ListingReport> = {
      content: [],
      totalElements: 0,
      totalPages: 0,
      number: 2,
    };
    getSpy.mockResolvedValueOnce(envelope(page));
    const client = makeClient();
    const { result } = renderHook(() => useAdminReports(2, 'OPEN', true, 5), {
      wrapper: wrapperFor(client),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(getSpy).toHaveBeenCalledWith('/admin/reports', {
      params: { page: 2, size: 5, status: 'OPEN' },
    });
  });

  it('useAdminReports stays idle when disabled (anonymous page guard)', async () => {
    const client = makeClient();
    const { result } = renderHook(() => useAdminReports(0, '', false), {
      wrapper: wrapperFor(client),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('useAdminReports propagates the failure shape so the page can render its error state', async () => {
    const failure = {
      isAxiosError: true,
      response: { status: 403, data: { code: 403, message: 'forbidden', data: null } },
    };
    getSpy.mockRejectedValueOnce(failure);
    const client = makeClient();
    const { result } = renderHook(() => useAdminReports(), { wrapper: wrapperFor(client) });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toBe(failure);
    expect(result.current.data).toBeUndefined();
  });

  it('useDismissReport posts to the dismiss route and invalidates the reports subtree', async () => {
    postSpy.mockResolvedValue(envelope({ ...fakeReport, status: 'DISMISSED' }));
    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');
    const { result } = renderHook(() => useDismissReport(), { wrapper: wrapperFor(client) });

    result.current.mutate(9);

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(postSpy).toHaveBeenCalledWith('/admin/reports/9/dismiss', {});
    expect(result.current.data?.status).toBe('DISMISSED');
    const keys = invalidateSpy.mock.calls.map((call) =>
      JSON.stringify((call[0] as { queryKey?: unknown } | undefined)?.queryKey),
    );
    expect(keys).toContain(JSON.stringify(['admin', 'reports']));
  });

  it('useActionReport posts to the action route and invalidates the reports subtree', async () => {
    postSpy.mockResolvedValue(envelope({ ...fakeReport, status: 'ACTIONED' }));
    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');
    const { result } = renderHook(() => useActionReport(), { wrapper: wrapperFor(client) });

    result.current.mutate(9);

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(postSpy).toHaveBeenCalledWith('/admin/reports/9/action', {});
    expect(result.current.data?.status).toBe('ACTIONED');
    const keys = invalidateSpy.mock.calls.map((call) =>
      JSON.stringify((call[0] as { queryKey?: unknown } | undefined)?.queryKey),
    );
    expect(keys).toContain(JSON.stringify(['admin', 'reports']));
  });
});
