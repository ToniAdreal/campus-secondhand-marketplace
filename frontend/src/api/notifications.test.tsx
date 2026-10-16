import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import {
  describeNotification,
  useDeleteNotification,
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotifications,
  useUnreadNotificationCount,
  type Notification,
} from './notifications';
import type { Page } from './items';

const getSpy = vi.spyOn(api, 'get');
const postSpy = vi.spyOn(api, 'post');
const deleteSpy = vi.spyOn(api, 'delete');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
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

const paidNotification: Notification = {
  id: 11,
  type: 'ORDER_PAID',
  orderId: 42,
  offerId: null,
  createdAt: '2026-10-13T08:00:00Z',
  readAt: null,
};

const page: Page<Notification> = {
  content: [paidNotification],
  totalElements: 1,
  totalPages: 1,
  number: 0,
};

describe('notifications api hooks (#157)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('GETs /notifications and unwraps the page envelope', async () => {
    getSpy.mockResolvedValueOnce(envelope(page));

    const { result } = renderHook(() => useNotifications(), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/notifications');
    expect(result.current.data?.content).toEqual([paidNotification]);
    expect(result.current.data?.content[0].readAt).toBeNull();
  });

  it('does not fetch while disabled (anonymous)', async () => {
    const { result } = renderHook(() => useNotifications(false), {
      wrapper: wrapperFor(makeClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('GETs /notifications/unread-count for the bell', async () => {
    getSpy.mockResolvedValueOnce(envelope(2));

    const { result } = renderHook(() => useUnreadNotificationCount(), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/notifications/unread-count');
    expect(result.current.data).toBe(2);
  });

  it('mark-read POSTs to /notifications/{id}/read and invalidates the notifications subtree', async () => {
    const readNotification = { ...paidNotification, readAt: '2026-10-13T08:05:00Z' };
    postSpy.mockResolvedValueOnce(envelope(readNotification));
    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useMarkNotificationRead(), {
      wrapper: wrapperFor(client),
    });
    result.current.mutate(11);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(postSpy).toHaveBeenCalledWith('/notifications/11/read');
    expect(result.current.data).toEqual(readNotification);
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['notifications'] });
  });

  it('mark-all-read POSTs to /notifications/read-all, returns the stamped count and invalidates', async () => {
    postSpy.mockResolvedValueOnce(envelope(3));
    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useMarkAllNotificationsRead(), {
      wrapper: wrapperFor(client),
    });
    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(postSpy).toHaveBeenCalledWith('/notifications/read-all');
    expect(result.current.data).toBe(3);
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['notifications'] });
  });

  it('delete DELETEs /notifications/{id} and invalidates the notifications subtree', async () => {
    deleteSpy.mockResolvedValueOnce({} as never);
    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useDeleteNotification(), {
      wrapper: wrapperFor(client),
    });
    result.current.mutate(11);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(deleteSpy).toHaveBeenCalledWith('/notifications/11');
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['notifications'] });
  });

  it('describes every notification type in words', () => {
    expect(describeNotification('ORDER_PAID')).toMatch(/paid/i);
    expect(describeNotification('ORDER_COMPLETED')).toMatch(/completed/i);
    expect(describeNotification('ORDER_REFUNDED')).toMatch(/refunded/i);
    expect(describeNotification('ORDER_CANCELLED')).toMatch(/cancelled/i);
    expect(describeNotification('ORDER_EXPIRED')).toMatch(/expired/i);
  });
});
