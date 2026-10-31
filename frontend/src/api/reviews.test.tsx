import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import {
  describeReviewEditError,
  describeReviewError,
  describeReviewResponseError,
  useCreateReview,
  useEditReview,
  useRespondToReview,
  useSellerReviews,
  type SellerReview,
} from './reviews';
import type { Page } from './items';

const getSpy = vi.spyOn(api, 'get');
const postSpy = vi.spyOn(api, 'post');
const putSpy = vi.spyOn(api, 'put');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function errorWith(code: number, message = '') {
  return { response: { data: { code, message } } };
}

function wrapperFor(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

const review: SellerReview = {
  id: 21,
  orderId: 31,
  buyerId: 5,
  reviewerUsername: 'buyer',
  sellerId: 3,
  rating: 5,
  body: 'Great seller',
  sellerResponse: null,
  createdAt: '2026-10-05T00:00:00Z',
};

describe('reviews api hooks (#175)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('POSTs /orders/{id}/review with the rating and optional body', async () => {
    postSpy.mockResolvedValueOnce(envelope(review));

    const { result } = renderHook(() => useCreateReview(), {
      wrapper: wrapperFor(makeClient()),
    });
    result.current.mutate({ orderId: 31, rating: 5, body: 'Great seller' });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(postSpy).toHaveBeenCalledWith('/orders/31/review', {
      rating: 5,
      body: 'Great seller',
    });
    expect(result.current.data).toEqual(review);
  });

  it('omits the body entirely for a star-only review', async () => {
    postSpy.mockResolvedValueOnce(envelope({ ...review, body: null }));

    const { result } = renderHook(() => useCreateReview(), {
      wrapper: wrapperFor(makeClient()),
    });
    result.current.mutate({ orderId: 31, rating: 4 });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(postSpy).toHaveBeenCalledWith('/orders/31/review', { rating: 4 });
  });

  it('GETs /users/{id}/reviews with the first page and returns the page', async () => {
    const page: Page<SellerReview> = {
      content: [review],
      totalElements: 1,
      totalPages: 1,
      number: 0,
    };
    getSpy.mockResolvedValueOnce(envelope(page));

    const { result } = renderHook(() => useSellerReviews(3), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/users/3/reviews', {
      params: { page: 0, size: 20 },
    });
    expect(result.current.data).toEqual(page);
  });

  it('maps review errors to plain copy', () => {
    expect(describeReviewError(errorWith(409))).toBe('You have already reviewed this order.');
    expect(describeReviewError(errorWith(422))).toBe('Only a completed order can be reviewed.');
    expect(describeReviewError(errorWith(403))).toBe(
      'Only the buyer of this order can review it.',
    );
    expect(describeReviewError(errorWith(404))).toBe('This order no longer exists.');
  });
});

describe('seller responses api hook (#246)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('PUTs /reviews/{id}/response with the response text', async () => {
    putSpy.mockResolvedValueOnce(
      envelope({ ...review, sellerResponse: 'Thanks for the kind words!' }),
    );

    const { result } = renderHook(() => useRespondToReview(), {
      wrapper: wrapperFor(makeClient()),
    });
    result.current.mutate({ reviewId: 21, response: 'Thanks for the kind words!' });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(putSpy).toHaveBeenCalledWith('/reviews/21/response', {
      response: 'Thanks for the kind words!',
    });
    expect(result.current.data).toEqual({
      ...review,
      sellerResponse: 'Thanks for the kind words!',
    });
  });

  it('maps response errors to plain copy', () => {
    expect(describeReviewResponseError(errorWith(400))).toBe(
      'Please write a response up to 1000 characters.',
    );
    expect(describeReviewResponseError(errorWith(403))).toBe(
      'Only the seller can respond to this review.',
    );
    expect(describeReviewResponseError(errorWith(401))).toBe('Please log in to respond.');
    expect(describeReviewResponseError(errorWith(404))).toBe(
      'This review no longer exists.',
    );
  });
});

describe('buyer review edits api hook (#254)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('PUTs /reviews/{id} with the full replacement rating and body', async () => {
    putSpy.mockResolvedValueOnce(
      envelope({ ...review, rating: 5, body: 'On reflection, perfect' }),
    );

    const { result } = renderHook(() => useEditReview(), {
      wrapper: wrapperFor(makeClient()),
    });
    result.current.mutate({ reviewId: 21, rating: 5, body: 'On reflection, perfect' });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(putSpy).toHaveBeenCalledWith('/reviews/21', {
      rating: 5,
      body: 'On reflection, perfect',
    });
    expect(result.current.data).toEqual({
      ...review,
      rating: 5,
      body: 'On reflection, perfect',
    });
  });

  it('maps edit errors to plain copy', () => {
    expect(describeReviewEditError(errorWith(422))).toBe(
      'This review can no longer be edited — the edit window has closed or the seller has already responded.',
    );
    expect(describeReviewEditError(errorWith(400))).toBe(
      'Please pick a rating from 1 to 5 (text up to 500 characters).',
    );
    expect(describeReviewEditError(errorWith(403))).toBe(
      'Only the buyer of this order can edit it.',
    );
    expect(describeReviewEditError(errorWith(401))).toBe(
      'Please log in to edit your review.',
    );
    expect(describeReviewEditError(errorWith(404))).toBe(
      'This review no longer exists.',
    );
  });
});

describe('describeReviewError', () => {
  // Backlog #287 follow-up: describeReviewError (src/api/
  // reviews.ts 151-175) carried 17 gap mutants in the 2026-10-31
  // mapper-only report. The existing #175 test pins only the
  // 409/422/403/404 branches with an empty message, so the 400
  // and 401 branches, the optional-chain and ?? edges, and the
  // envelope-shape edges were unreached. This matrix covers every
  // branch plus those edges: a response without data, data
  // without a message, a message without a code, and no envelope
  // at all all fall through to the generic copy or the verbatim
  // message, while a server message on an unknown code is kept
  // verbatim.
  function envelopeError(code: number, message: string) {
    return { response: { data: { code, message } } };
  }

  it.each([
    ['already reviewed', envelopeError(409, 'raw'), 'You have already reviewed this order.'],
    ['not completed', envelopeError(422, 'raw'), 'Only a completed order can be reviewed.'],
    ['bad rating', envelopeError(400, 'raw'), 'Please pick a rating from 1 to 5 (text up to 500 characters).'],
    ['forbidden', envelopeError(403, 'raw'), 'Only the buyer of this order can review it.'],
    ['anonymous', envelopeError(401, 'raw'), 'Please log in to leave a review.'],
    ['gone', envelopeError(404, 'raw'), 'This order no longer exists.'],
    ['unknown code keeps the server message', envelopeError(500, 'database unavailable'), 'database unavailable'],
    ['unknown code with an empty message falls back', envelopeError(500, ''), 'Something went wrong. Please try again.'],
    ['message without a code keeps the server message', { response: { data: { message: 'database unavailable' } } }, 'database unavailable'],
    ['response without data falls back', { response: {} }, 'Something went wrong. Please try again.'],
    ['data without a message falls back', { response: { data: { code: 500 } } }, 'Something went wrong. Please try again.'],
    ['no envelope at all', new Error('boom'), 'Something went wrong. Please try again.'],
    ['empty object', {}, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeReviewError(error)).toBe(expected);
  });
});
