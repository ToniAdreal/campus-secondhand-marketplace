import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import {
  describeAdminDisputesError,
  describeDisputeError,
  describeDisputeEvidenceError,
  useAddDisputeEvidence,
  useAdminDisputes,
  useDisputeEvidence,
  useOpenDispute,
  useOrderDispute,
  useRemoveDisputeEvidence,
  useResolveDisputeRefund,
  type DisputeEvidence,
  type OrderDispute,
} from './disputes';
import { describeNotification } from './notifications';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');
const deleteSpy = vi.spyOn(api, 'delete');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function envelopeError(code: number, message: string) {
  return { response: { data: { code, message } } };
}

const fakeDispute: OrderDispute = {
  id: 3,
  orderId: 9,
  buyerId: 5,
  sellerId: 3,
  reason: 'the parcel never arrived',
  status: 'OPEN',
  previousStatus: 'PAID',
  listingRestored: null,
  createdAt: '2026-10-14T08:00:00Z',
  resolvedAt: null,
  resolutionNote: null,
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

describe('describeDisputeError', () => {
  it.each([
    ['window closed', envelopeError(422, 'the dispute window for this order has closed'), 'The dispute window for this order has closed.'],
    ['already resolved', envelopeError(422, 'this order was already disputed and the dispute was resolved'), 'This order was already disputed and resolved.'],
    ['not disputable', envelopeError(422, 'order is PENDING, only PAID or COMPLETED orders can be disputed'), 'Only a paid or completed order can be disputed.'],
    ['bad reason', envelopeError(400, 'validation'), 'Please describe the problem (up to 500 characters).'],
    ['race', envelopeError(409, 'order was updated concurrently'), 'Someone just acted on this order — please refresh and try again.'],
    ['not buyer', envelopeError(403, 'only the buyer'), 'Only the buyer of this order can dispute it.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in to dispute this order.'],
    ['unknown', envelopeError(404, 'order not found'), 'This order no longer exists.'],
    ['no envelope at all', new Error('boom'), 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeDisputeError(error)).toBe(expected);
  });
});

describe('describeAdminDisputesError', () => {
  it.each([
    ['forbidden', envelopeError(403, 'forbidden'), 'You are not authorized to view the dispute queue.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in again to view the dispute queue.'],
    ['bad filter', envelopeError(400, 'bad'), 'That filter is not valid.'],
    ['unknown', envelopeError(404, 'dispute not found'), 'That dispute no longer exists.'],
    ['cross resolution', envelopeError(422, 'already resolved'), 'That dispute has already been resolved the other way.'],
    ['race', envelopeError(409, 'concurrent'), 'Someone just resolved this dispute — please refresh.'],
  ])('%s', (_label, error, expected) => {
    expect(describeAdminDisputesError(error)).toBe(expected);
  });
});

describe('dispute hooks', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('useOpenDispute posts the reason to the nested order route and unwraps the envelope', async () => {
    postSpy.mockResolvedValue(envelope(fakeDispute));
    const client = makeClient();
    const { result } = renderHook(() => useOpenDispute(), { wrapper: wrapperFor(client) });

    result.current.mutate({ orderId: 9, reason: 'the parcel never arrived' });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(postSpy).toHaveBeenCalledWith('/orders/9/dispute', {
      reason: 'the parcel never arrived',
    });
    expect(result.current.data).toEqual(fakeDispute);
  });

  it('useAdminDisputes reads /admin/disputes with page params and omits an unset status', async () => {
    const page = { content: [fakeDispute], totalElements: 1, totalPages: 1, number: 0 };
    getSpy.mockResolvedValue(envelope(page));
    const client = makeClient();
    const { result } = renderHook(() => useAdminDisputes(0, '', true), {
      wrapper: wrapperFor(client),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(getSpy).toHaveBeenCalledWith('/admin/disputes', { params: { page: 0, size: 20 } });
    expect(result.current.data).toEqual(page);
  });

  it('useAdminDisputes sends an allowlisted status filter when set', async () => {
    const page = { content: [], totalElements: 0, totalPages: 0, number: 0 };
    getSpy.mockResolvedValue(envelope(page));
    const client = makeClient();
    const { result } = renderHook(() => useAdminDisputes(0, 'OPEN', true), {
      wrapper: wrapperFor(client),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(getSpy).toHaveBeenCalledWith('/admin/disputes', {
      params: { page: 0, size: 20, status: 'OPEN' },
    });
  });

  it('useResolveDisputeRefund posts to the resolve-refund route', async () => {
    const resolved = { ...fakeDispute, status: 'RESOLVED_REFUND', listingRestored: true };
    postSpy.mockResolvedValue(envelope(resolved));
    const client = makeClient();
    const { result } = renderHook(() => useResolveDisputeRefund(), {
      wrapper: wrapperFor(client),
    });

    result.current.mutate({ id: 3, resolutionNote: 'Carrier confirms non-delivery.' });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(postSpy).toHaveBeenCalledWith('/admin/disputes/3/resolve-refund', {
      resolutionNote: 'Carrier confirms non-delivery.',
    });
    expect(result.current.data).toEqual(resolved);
  });
});

describe('dispute evidence hooks (backlog #200)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  const fakeEvidence: DisputeEvidence = {
    id: 11,
    disputeId: 3,
    uploaderId: 5,
    url: '/uploads/photo-11.png',
    createdAt: '2026-10-20T08:00:00Z',
  };

  it('useOrderDispute reads the dispute nested under its order', async () => {
    getSpy.mockResolvedValue(envelope(fakeDispute));
    const client = makeClient();
    const { result } = renderHook(() => useOrderDispute(9), {
      wrapper: wrapperFor(client),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(getSpy).toHaveBeenCalledWith('/orders/9/dispute');
    expect(result.current.data).toEqual(fakeDispute);
  });

  it('useDisputeEvidence lists the evidence of one dispute', async () => {
    getSpy.mockResolvedValue(envelope([fakeEvidence]));
    const client = makeClient();
    const { result } = renderHook(() => useDisputeEvidence(3), {
      wrapper: wrapperFor(client),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(getSpy).toHaveBeenCalledWith('/disputes/3/evidence');
    expect(result.current.data).toEqual([fakeEvidence]);
  });

  it('useAddDisputeEvidence posts the file as FormData to the evidence route', async () => {
    postSpy.mockResolvedValue(envelope(fakeEvidence));
    const client = makeClient();
    const { result } = renderHook(() => useAddDisputeEvidence(), {
      wrapper: wrapperFor(client),
    });
    const file = new File(['png-bytes'], 'damage.png', { type: 'image/png' });

    result.current.mutate({ disputeId: 3, file });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const [path, form] = postSpy.mock.calls[0] as unknown as [string, FormData];
    expect(path).toBe('/disputes/3/evidence');
    expect(form).toBeInstanceOf(FormData);
    expect(form.get('file')).toBe(file);
    expect(result.current.data).toEqual(fakeEvidence);
  });

  it('useRemoveDisputeEvidence deletes the nested evidence route', async () => {
    deleteSpy.mockResolvedValue(envelope(null));
    const client = makeClient();
    const { result } = renderHook(() => useRemoveDisputeEvidence(), {
      wrapper: wrapperFor(client),
    });

    result.current.mutate({ disputeId: 3, evidenceId: 11 });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(deleteSpy).toHaveBeenCalledWith('/disputes/3/evidence/11');
  });
});

describe('describeDisputeEvidenceError', () => {
  it.each([
    ['bad file', envelopeError(400, 'image content does not match declared type'), 'That file could not be accepted — please attach a PNG, JPEG, WebP or GIF image within the size limit.'],
    ['anonymous', envelopeError(401, 'unauthorized'), 'Please log in to manage dispute evidence.'],
    ['not uploader', envelopeError(403, 'only the uploader'), 'Only the person who attached this photo can remove it.'],
    ['unknown', envelopeError(404, 'dispute not found'), 'This dispute no longer exists.'],
    ['resolved', envelopeError(422, 'evidence can only be added while the dispute is open'), 'This dispute has been resolved — evidence is closed.'],
    ['no envelope at all', new Error('boom'), 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeDisputeEvidenceError(error)).toBe(expected);
  });
});

describe('dispute notification copy', () => {
  it('describes the three dispute notification types', () => {
    expect(describeNotification('ORDER_DISPUTED')).toBe(
      'A buyer disputed an order for your listing.',
    );
    expect(describeNotification('DISPUTE_RESOLVED_REFUND')).toBe(
      'A dispute was resolved with a refund.',
    );
    expect(describeNotification('DISPUTE_RESOLVED_RELEASE')).toBe(
      'A dispute was resolved and the order was released.',
    );
  });
});
