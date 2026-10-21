import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import {
  describeNotification,
  notificationKeys,
  useDeleteNotification,
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotificationStream,
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
  savedSearchId: null,
  matchedListingCount: null,
  itemId: null,
  oldPriceCents: null,
  newPriceCents: null,
  appealId: null,
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
    // #178: the alert copy names the remedy and never claims a block.
    expect(describeNotification('LOGIN_NEW_DEVICE')).toMatch(/new sign-in/i);
    expect(describeNotification('LOGIN_NEW_DEVICE')).toMatch(/revoke your sessions/i);
    expect(describeNotification('LOGIN_NEW_DEVICE')).not.toMatch(/blocked/i);
    // #189: the price-drop line states the old (snapshot) and new prices.
    expect(describeNotification('FAVORITE_PRICE_DROP', null, 10000, 8000)).toBe(
      'A listing you saved dropped in price from ¥100.00 to ¥80.00.',
    );
    expect(describeNotification('FAVORITE_PRICE_DROP')).toBe(
      'A listing you saved dropped in price.',
    );
    // #182: the batched saved-search line carries its match count.
    expect(describeNotification('SAVED_SEARCH_MATCH', 5)).toBe(
      '5 new listings match your saved search.',
    );
    expect(describeNotification('SAVED_SEARCH_MATCH', 1)).toBe(
      '1 new listing matches your saved search.',
    );
    // #191: the eviction line names the cap and the oldest session —
    // it is a sign-out notice, never a block claim.
    expect(describeNotification('SESSION_EVICTED')).toMatch(/oldest session/i);
    expect(describeNotification('SESSION_EVICTED')).toMatch(/at most 5 devices/i);
    expect(describeNotification('SESSION_EVICTED')).not.toMatch(/blocked/i);
    // #208: the email-change alert lands at request time and names the
    // remedy — it never claims the address already changed.
    expect(describeNotification('EMAIL_CHANGE_REQUESTED')).toMatch(/email change was requested/i);
    expect(describeNotification('EMAIL_CHANGE_REQUESTED')).toMatch(/change your password/i);
    expect(describeNotification('EMAIL_CHANGE_REQUESTED')).not.toMatch(/has changed/i);
    // #193: the counter-offer lines name the step each side takes next.
    expect(describeNotification('OFFER_COUNTERED')).toMatch(/countered your offer/i);
    expect(describeNotification('OFFER_COUNTER_ACCEPTED')).toMatch(/accepted your counter/i);
    expect(describeNotification('OFFER_COUNTER_DECLINED')).toMatch(/declined your counter/i);
    // #190: appeal copy is generic — it never claims the listing was
    // restored (that fact lives on the appeal's listingRestored).
    expect(describeNotification('REPORT_APPEAL_UPHELD')).toBe('Your appeal was upheld.');
    expect(describeNotification('REPORT_APPEAL_UPHELD')).not.toMatch(/restored/i);
    expect(describeNotification('REPORT_APPEAL_REJECTED')).toMatch(/withdrawal stands/i);
  });
});

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  readonly url: string;
  closed = false;
  onerror: (() => void) | null = null;
  private listeners = new Map<string, Array<() => void>>();

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: () => void) {
    const list = this.listeners.get(type) ?? [];
    list.push(listener);
    this.listeners.set(type, list);
  }

  emit(type: string) {
    for (const listener of this.listeners.get(type) ?? []) {
      listener();
    }
  }

  close() {
    this.closed = true;
  }
}

describe('useNotificationStream (#203)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    FakeEventSource.instances = [];
  });

  it('opens the stream with a one-time ticket and invalidates on event', async () => {
    vi.stubGlobal('EventSource', FakeEventSource);
    postSpy.mockResolvedValue(envelope({ ticket: 'ticket-abc' }));
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    const { unmount } = renderHook(() => useNotificationStream(true), {
      wrapper: wrapperFor(queryClient),
    });

    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));
    expect(postSpy).toHaveBeenCalledWith('/notifications/stream-ticket');
    const source = FakeEventSource.instances[0];
    expect(source.url).toBe('/api/notifications/stream?ticket=ticket-abc');

    source.emit('notification');
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: notificationKeys.all });

    unmount();
    expect(source.closed).toBe(true);
  });

  it('stays closed while disabled and never asks for a ticket', async () => {
    vi.stubGlobal('EventSource', FakeEventSource);
    postSpy.mockClear();
    postSpy.mockResolvedValue(envelope({ ticket: 'ticket-abc' }));
    const queryClient = makeClient();

    renderHook(() => useNotificationStream(false), { wrapper: wrapperFor(queryClient) });

    await Promise.resolve();
    expect(postSpy).not.toHaveBeenCalledWith('/notifications/stream-ticket');
    expect(FakeEventSource.instances).toHaveLength(0);
  });

  it('a stream error closes the source — the refetch fallback remains', async () => {
    vi.stubGlobal('EventSource', FakeEventSource);
    postSpy.mockResolvedValue(envelope({ ticket: 'ticket-abc' }));
    const queryClient = makeClient();

    renderHook(() => useNotificationStream(true), { wrapper: wrapperFor(queryClient) });

    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));
    const source = FakeEventSource.instances[0];
    source.onerror?.();
    expect(source.closed).toBe(true);
  });
});
