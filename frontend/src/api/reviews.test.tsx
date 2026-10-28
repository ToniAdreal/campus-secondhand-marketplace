import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import {
  describeReviewError,
  describeReviewResponseError,
  useCreateReview,
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
