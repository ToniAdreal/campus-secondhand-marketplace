import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import {
  describeMarkSoldError,
  describePublishError,
  describeRenewError,
  describeReorderPhotosError,
  isListingExpired,
  isListingExpiringSoon,
  itemKeys,
  listingPhotoUrls,
  publishListing,
  useItems,
  useItemSuggestions,
  type Item,
  type ItemPhoto,
} from './items';

const getSpy = vi.spyOn(api, 'get');
const postSpy = vi.spyOn(api, 'post');

function envelopePage() {
  return {
    data: {
      code: 0,
      message: 'ok',
      data: { content: [], totalElements: 0, totalPages: 0, number: 0 },
    },
  } as never;
}

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

describe('useItems request params', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('omits q and categoryId when neither filter is set', async () => {
    getSpy.mockResolvedValue(envelopePage());

    const { result } = renderHook(() => useItems(0), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/items', { params: { page: 0, size: 20 } });
  });

  it('sends categoryId on its own', async () => {
    getSpy.mockResolvedValue(envelopePage());

    const { result } = renderHook(() => useItems(0, '', 3), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/items', {
      params: { page: 0, size: 20, categoryId: 3 },
    });
  });

  it('sends q and categoryId together (the backend ANDs both filters)', async () => {
    getSpy.mockResolvedValue(envelopePage());

    const { result } = renderHook(() => useItems(0, 'bike', 2), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/items', {
      params: { page: 0, size: 20, q: 'bike', categoryId: 2 },
    });
  });

  it('treats a null categoryId as no filter', async () => {
    getSpy.mockResolvedValue(envelopePage());

    const { result } = renderHook(() => useItems(0, '', null), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/items', { params: { page: 0, size: 20 } });
  });

  it('sends price bounds and a non-default sort, omitting the default sort', async () => {
    getSpy.mockResolvedValue(envelopePage());

    const { result } = renderHook(
      () => useItems(0, '', null, { minPriceCents: 500, maxPriceCents: 5000, sort: 'price-asc' }),
      { wrapper },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/items', {
      params: { page: 0, size: 20, minPriceCents: 500, maxPriceCents: 5000, sort: 'price-asc' },
    });
  });

  it('omits sort=newest (the backend default) from the request', async () => {
    getSpy.mockResolvedValue(envelopePage());

    const { result } = renderHook(() => useItems(0, '', null, { sort: 'newest' }), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/items', { params: { page: 0, size: 20 } });
  });

  it('keys the cache by category so filtered and unfiltered lists never collide', () => {
    expect(itemKeys.list(0, '')).not.toEqual(itemKeys.list(0, '', 2));
    expect(itemKeys.list(0, 'bike', 2)).not.toEqual(itemKeys.list(0, 'bike'));
  });
});

describe('useItems keepPreviousData (backlog #144)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('keeps the previous page visible while the next page loads', async () => {
    const page0 = {
      data: {
        code: 0,
        message: 'ok',
        data: { content: [{ id: 1 }], totalElements: 21, totalPages: 2, number: 0 },
      },
    } as never;
    let resolvePage1: (value: never) => void = () => undefined;
    const page1Pending = new Promise<never>((resolve) => {
      resolvePage1 = resolve;
    });
    getSpy.mockResolvedValueOnce(page0).mockReturnValueOnce(page1Pending);

    const { result, rerender } = renderHook(({ page }: { page: number }) => useItems(page), {
      wrapper,
      initialProps: { page: 0 },
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.number).toBe(0);

    rerender({ page: 1 });

    // The page-1 request is still in flight, but the page-0 rows stay.
    expect(result.current.data?.number).toBe(0);
    expect(result.current.isPreviousData).toBe(true);

    resolvePage1({
      data: {
        code: 0,
        message: 'ok',
        data: { content: [{ id: 21 }], totalElements: 21, totalPages: 2, number: 1 },
      },
    } as never);
    await waitFor(() => expect(result.current.data?.number).toBe(1));
    expect(result.current.isPreviousData).toBe(false);
  });
});

describe('listingPhotoUrls', () => {
  const photo = (id: number, url: string, position: number): ItemPhoto => ({ id, url, position });

  it('returns the gallery ordered by position, primary first', () => {
    expect(
      listingPhotoUrls({
        photoUrl: null,
        photos: [photo(2, '/uploads/b.png', 1), photo(1, '/uploads/a.png', 0)],
      }),
    ).toEqual(['/uploads/a.png', '/uploads/b.png']);
  });

  it('prefers the gallery over the legacy photoUrl when both exist', () => {
    expect(
      listingPhotoUrls({
        photoUrl: '/uploads/legacy.png',
        photos: [photo(1, '/uploads/a.png', 0)],
      }),
    ).toEqual(['/uploads/a.png']);
  });

  it('falls back to the legacy photoUrl when the gallery is empty', () => {
    expect(listingPhotoUrls({ photoUrl: '/uploads/legacy.png', photos: [] })).toEqual([
      '/uploads/legacy.png',
    ]);
  });

  it('is empty when the listing has no photo at all', () => {
    expect(listingPhotoUrls({ photoUrl: null, photos: [] })).toEqual([]);
  });
});

describe('listing expiry helpers (backlog #177)', () => {
  const now = new Date('2026-10-16T00:00:00.000Z');
  const base: Item = {
    id: 1,
    title: 'Lamp',
    description: null,
    priceCents: 100,
    status: 'AVAILABLE',
    sellerId: 3,
    sellerUsername: 'seller',
    categoryId: null,
    categoryName: null,
    photoUrl: null,
    photos: [],
  };

  it('treats the stored EXPIRED status as expired regardless of the deadline', () => {
    expect(isListingExpired({ ...base, status: 'EXPIRED', expiresAt: '2999-01-01T00:00:00.000Z' }, now)).toBe(true);
  });

  it('treats an AVAILABLE listing past its deadline as expired (not yet flipped by the job)', () => {
    expect(isListingExpired({ ...base, expiresAt: '2026-10-15T00:00:00.000Z' }, now)).toBe(true);
    expect(isListingExpired({ ...base, expiresAt: '2026-10-20T00:00:00.000Z' }, now)).toBe(false);
    expect(isListingExpired({ ...base, status: 'RESERVED', expiresAt: '2026-10-15T00:00:00.000Z' }, now)).toBe(false);
    expect(isListingExpired({ ...base, expiresAt: null }, now)).toBe(false);
  });

  it('flags a live listing inside the 7-day renew window as expiring soon', () => {
    expect(isListingExpiringSoon({ ...base, expiresAt: '2026-10-20T00:00:00.000Z' }, now)).toBe(true);
    expect(isListingExpiringSoon({ ...base, expiresAt: '2026-11-20T00:00:00.000Z' }, now)).toBe(false);
    expect(isListingExpiringSoon({ ...base, expiresAt: '2026-10-15T00:00:00.000Z' }, now)).toBe(false);
  });

  it('maps renew failures to friendly copy', () => {
    const err = (code: number) => ({ response: { data: { code, message: 'raw' } } });
    expect(describeRenewError(err(422))).toMatch(/cannot be renewed yet/i);
    expect(describeRenewError(err(403))).toMatch(/only the seller/i);
    expect(describeRenewError(err(404))).toMatch(/no longer exists/i);
  });
});

describe('listing publish (backlog #198)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('publishListing posts to /items/{id}/publish and unwraps the envelope', async () => {
    postSpy.mockResolvedValue({
      data: { code: 0, message: 'ok', data: { id: 9, status: 'AVAILABLE' } },
    } as never);
    const item = await publishListing(9);
    expect(postSpy).toHaveBeenCalledWith('/items/9/publish');
    expect(item.status).toBe('AVAILABLE');
  });

  it('maps publish failures to friendly copy', () => {
    const err = (code: number) => ({ response: { data: { code, message: 'raw' } } });
    expect(describePublishError(err(422))).toMatch(/only a draft/i);
    expect(describePublishError(err(403))).toMatch(/only the seller/i);
    expect(describePublishError(err(404))).toMatch(/no longer exists/i);
  });
});

describe('useItemSuggestions request (backlog #235)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('fetches titles for the trimmed query', async () => {
    getSpy.mockResolvedValue({
      data: {
        code: 0,
        message: 'ok',
        data: [
          { id: 1, title: 'Bike helmet' },
          { id: 2, title: 'Bike lock' },
        ],
      },
    } as never);

    const { result } = renderHook(() => useItemSuggestions('  bike  '), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/items/suggestions', { params: { q: 'bike' } });
    expect(result.current.data).toEqual([
      { id: 1, title: 'Bike helmet' },
      { id: 2, title: 'Bike lock' },
    ]);
  });

  it('stays disabled for a blank query and fires no request', async () => {
    const { result } = renderHook(() => useItemSuggestions('   '), { wrapper });

    expect(result.current.isFetching).toBe(false);
    expect(getSpy).not.toHaveBeenCalled();
  });
});

describe('describeReorderPhotosError', () => {
  // Backlog #287 follow-up: describeReorderPhotosError (src/api/
  // items.ts 339-360) carried 34 gap mutants in the 2026-10-31
  // mapper-only report — 25 NoCoverage (the 409/403/401/404
  // branches and the fallback, none of which any existing test
  // reaches) plus 9 Survived (the optional-chain and ?? edges and
  // the 400-or-regex condition, which no existing test
  // distinguishes). This matrix covers every branch plus the
  // envelope-shape edges: the 400 branch fires on the code alone
  // and on the message alone (case-insensitive, substring), the
  // first branch wins over a later code, a response without data,
  // data without a message, and no envelope at all all fall
  // through to the generic copy, while a server message on an
  // unknown code is kept verbatim.
  function envelopeError(code: number, message: string) {
    return { response: { data: { code, message } } };
  }

  it.each([
    ['400 code alone', envelopeError(400, 'raw'), 'The gallery changed since this page loaded — reload and try again.'],
    ['exactly-once message alone', envelopeError(500, 'each photo must appear exactly once'), 'The gallery changed since this page loaded — reload and try again.'],
    ['exactly-once message, case-insensitive', envelopeError(500, 'EXACTLY ONCE in the list'), 'The gallery changed since this page loaded — reload and try again.'],
    ['first branch wins over 409', envelopeError(409, 'each photo must appear exactly once'), 'The gallery changed since this page loaded — reload and try again.'],
    ['conflict', envelopeError(409, 'raw'), 'This gallery was just changed elsewhere — reload to see the latest order.'],
    ['forbidden', envelopeError(403, 'raw'), 'Only the seller (or an admin) can reorder these photos.'],
    ['anonymous', envelopeError(401, 'raw'), 'Please log in to reorder these photos.'],
    ['gone', envelopeError(404, 'raw'), 'This listing no longer exists.'],
    ['unknown code keeps the server message', envelopeError(500, 'database unavailable'), 'database unavailable'],
    ['unknown code with an empty message falls back', envelopeError(500, ''), 'Something went wrong. Please try again.'],
    ['response without data falls back', { response: {} }, 'Something went wrong. Please try again.'],
    ['data without a message falls back', { response: { data: { code: 500 } } }, 'Something went wrong. Please try again.'],
    ['no envelope at all', new Error('boom'), 'Something went wrong. Please try again.'],
    ['empty object', {}, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeReorderPhotosError(error)).toBe(expected);
  });
});

describe('describeMarkSoldError', () => {
  // Backlog #287 follow-up: describeMarkSoldError (src/api/
  // items.ts 438-456) carried 29 gap mutants in the 2026-10-31
  // mapper-only report — 20 NoCoverage (the 403/401/404
  // branches and the fallback, none of which any existing test
  // reaches) plus 9 Survived (the optional-chain and ?? edges
  // and the 422-or-regex condition, which no existing test
  // distinguishes). This matrix covers every branch plus the
  // envelope-shape edges: the 422 branch fires on the code alone
  // and on the message alone (either regex alternative,
  // case-insensitive, substring), the first branch wins over a
  // later code, a response without data, data without a message,
  // and no envelope at all all fall through to the generic copy,
  // while a server message on an unknown code is kept verbatim.
  function envelopeError(code: number, message: string) {
    return { response: { data: { code, message } } };
  }

  it.each([
    ['422 code alone', envelopeError(422, 'raw'), 'This listing is already sold — it can no longer be marked sold.'],
    ['already-SOLD message alone', envelopeError(500, 'this listing is already SOLD'), 'This listing is already sold — it can no longer be marked sold.'],
    ['only-the-SOLD-transition message alone', envelopeError(500, 'only the SOLD transition is allowed here'), 'This listing is already sold — it can no longer be marked sold.'],
    ['already-SOLD message, case-insensitive', envelopeError(500, 'ALREADY SOLD'), 'This listing is already sold — it can no longer be marked sold.'],
    ['first branch wins over 403', envelopeError(403, 'already SOLD'), 'This listing is already sold — it can no longer be marked sold.'],
    ['forbidden', envelopeError(403, 'raw'), 'Only the seller (or an admin) can mark this listing sold.'],
    ['anonymous', envelopeError(401, 'raw'), 'Please log in to update this listing.'],
    ['gone', envelopeError(404, 'raw'), 'This listing no longer exists.'],
    ['unknown code keeps the server message', envelopeError(500, 'database unavailable'), 'database unavailable'],
    ['unknown code with an empty message falls back', envelopeError(500, ''), 'Something went wrong. Please try again.'],
    ['response without data falls back', { response: {} }, 'Something went wrong. Please try again.'],
    ['data without a message falls back', { response: { data: { code: 500 } } }, 'Something went wrong. Please try again.'],
    ['no envelope at all', new Error('boom'), 'Something went wrong. Please try again.'],
    ['empty object', {}, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeMarkSoldError(error)).toBe(expected);
  });
});

describe('describeRenewError', () => {
  // Backlog #287 follow-up: describeRenewError (src/api/
  // items.ts 391-410) carried 14 gap mutants in the 2026-10-31
  // mapper-only report. The existing #177 test pins only the
  // 422/403/404 branches with a 'raw' message, so the 401 branch,
  // the optional-chain and ?? edges, and the envelope-shape
  // edges were unreached. This matrix covers every branch plus
  // those edges: a response without data, data without a
  // message, a message without a code, and no envelope at all
  // all fall through to the generic copy or the verbatim
  // message, while a server message on an unknown code is kept
  // verbatim.
  function envelopeError(code: number, message: string) {
    return { response: { data: { code, message } } };
  }

  it.each([
    ['renewable-state', envelopeError(422, 'raw'), 'This listing cannot be renewed yet — only expired or expiring listings can.'],
    ['forbidden', envelopeError(403, 'raw'), 'Only the seller (or an admin) can renew this listing.'],
    ['anonymous', envelopeError(401, 'raw'), 'Please log in to renew this listing.'],
    ['gone', envelopeError(404, 'raw'), 'This listing no longer exists.'],
    ['unknown code keeps the server message', envelopeError(500, 'database unavailable'), 'database unavailable'],
    ['unknown code with an empty message falls back', envelopeError(500, ''), 'Something went wrong. Please try again.'],
    ['message without a code keeps the server message', { response: { data: { message: 'database unavailable' } } }, 'database unavailable'],
    ['response without data falls back', { response: {} }, 'Something went wrong. Please try again.'],
    ['data without a message falls back', { response: { data: { code: 500 } } }, 'Something went wrong. Please try again.'],
    ['no envelope at all', new Error('boom'), 'Something went wrong. Please try again.'],
    ['empty object', {}, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeRenewError(error)).toBe(expected);
  });
});

describe('describePublishError', () => {
  // Backlog #287 follow-up: describePublishError (src/api/
  // items.ts 482-500) carried 14 gap mutants in the 2026-10-31
  // mapper-only report. The existing #198 test pins only the
  // 422/403/404 branches with a 'raw' message, so the 401 branch,
  // the optional-chain and ?? edges, and the envelope-shape
  // edges were unreached. This matrix covers every branch plus
  // those edges: a response without data, data without a
  // message, a message without a code, and no envelope at all
  // all fall through to the generic copy or the verbatim
  // message, while a server message on an unknown code is kept
  // verbatim.
  function envelopeError(code: number, message: string) {
    return { response: { data: { code, message } } };
  }

  it.each([
    ['draft-only', envelopeError(422, 'raw'), 'Only a draft listing can be published.'],
    ['forbidden', envelopeError(403, 'raw'), 'Only the seller (or an admin) can publish this listing.'],
    ['anonymous', envelopeError(401, 'raw'), 'Please log in to publish this listing.'],
    ['gone', envelopeError(404, 'raw'), 'This listing no longer exists.'],
    ['unknown code keeps the server message', envelopeError(500, 'database unavailable'), 'database unavailable'],
    ['unknown code with an empty message falls back', envelopeError(500, ''), 'Something went wrong. Please try again.'],
    ['message without a code keeps the server message', { response: { data: { message: 'database unavailable' } } }, 'database unavailable'],
    ['response without data falls back', { response: {} }, 'Something went wrong. Please try again.'],
    ['data without a message falls back', { response: { data: { code: 500 } } }, 'Something went wrong. Please try again.'],
    ['no envelope at all', new Error('boom'), 'Something went wrong. Please try again.'],
    ['empty object', {}, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describePublishError(error)).toBe(expected);
  });
});
