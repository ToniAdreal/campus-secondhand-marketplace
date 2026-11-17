import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import {
  describeNotification,
  MAX_NOTIFICATION_STREAM_RECONNECTS,
  NOTIFICATION_STREAM_RECONNECT_BASE_DELAY_MS,
  notificationKeys,
  useDeleteNotification,
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotificationStream,
  useNotifications,
  useSetNotificationPreference,
  useUnreadNotificationCount,
  type Notification,
} from './notifications';
import type { Page } from './items';

const getSpy = vi.spyOn(api, 'get');
const postSpy = vi.spyOn(api, 'post');
const putSpy = vi.spyOn(api, 'put');
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

  it('a successful preference toggle invalidates the preferences catalogue (#388)', async () => {
    // #347 left two survivors in useSetNotificationPreference's
    // onSuccess: the invalidation was driven by the Settings toggle
    // test but never asserted at the hook level. Pin the exact
    // invalidateQueries argument — invalidating everything ({}) would
    // still refetch the catalogue, so only the exact-key assertion
    // kills the ObjectLiteral mutant.
    putSpy.mockResolvedValueOnce(
      envelope({ type: 'ORDER_PAID', label: 'Paid', security: false, enabled: false }),
    );
    const client = makeClient();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useSetNotificationPreference(), {
      wrapper: wrapperFor(client),
    });
    result.current.mutate({ type: 'ORDER_PAID', enabled: false });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(putSpy).toHaveBeenCalledWith('/notifications/preferences/ORDER_PAID', {
      enabled: false,
    });
    // The mutation resolves with the saved preference: assert the
    // data, not just success — the mutationFn `.then((res) =>
    // res.data.data)` ArrowFunction mutant survives on isSuccess
    // alone (it resolves with undefined instead).
    expect(result.current.data).toEqual({
      type: 'ORDER_PAID',
      label: 'Paid',
      security: false,
      enabled: false,
    });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: notificationKeys.preferences });
  });

  it('describes every notification type in words', () => {
    expect(describeNotification('ORDER_PAID')).toMatch(/paid/i);
    // #238: the shipped line names the transition for the buyer.
    expect(describeNotification('ORDER_SHIPPED')).toMatch(/shipped/i);
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
    // #209: the admin TOTP-reset line states the sign-out consequence
    // and the re-enrollment remedy — it never claims the holder did it.
    expect(describeNotification('TOTP_RESET_BY_ADMIN')).toMatch(/administrator reset/i);
    expect(describeNotification('TOTP_RESET_BY_ADMIN')).toMatch(/signed out everywhere/i);
    expect(describeNotification('TOTP_RESET_BY_ADMIN')).toMatch(/set up two-factor/i);
    // #193: the counter-offer lines name the step each side takes next.
    expect(describeNotification('OFFER_COUNTERED')).toMatch(/countered your offer/i);
    expect(describeNotification('OFFER_COUNTER_ACCEPTED')).toMatch(/accepted your counter/i);
    expect(describeNotification('OFFER_COUNTER_DECLINED')).toMatch(/declined your counter/i);
    // #226: one neutral expiry line serves both waiting parties —
    // the buyer whose offer went unanswered and the seller whose
    // counter went unaccepted — without naming who lapsed.
    expect(describeNotification('OFFER_EXPIRED')).toBe(
      'An offer expired before it was answered.',
    );
    // #190: appeal copy is generic — it never claims the listing was
    // restored (that fact lives on the appeal's listingRestored).
    expect(describeNotification('REPORT_APPEAL_UPHELD')).toBe('Your appeal was upheld.');
    expect(describeNotification('REPORT_APPEAL_UPHELD')).not.toMatch(/restored/i);
    expect(describeNotification('REPORT_APPEAL_REJECTED')).toMatch(/withdrawal stands/i);
    // #217: the follow-publish line names the relationship, not the
    // seller — and the type is in the catalogue's union, not the
    // 'Order update.' default.
    expect(describeNotification('SELLER_NEW_LISTING')).toBe(
      'A seller you follow published a new listing.',
    );
    expect(describeNotification('SELLER_NEW_LISTING')).not.toBe('Order update.');
    // #332: the payout line says "marked paid" — PAID is a ledger
    // state, never a bank transfer — and it is not the default line.
    expect(describeNotification('PAYOUT_PAID')).toBe('Your payout was marked paid.');
    expect(describeNotification('PAYOUT_PAID')).not.toBe('Order update.');
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
    vi.useRealTimers();
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

  it('a stream error reconnects with a fresh ticket and the new stream still invalidates (#340)', async () => {
    vi.useFakeTimers();
    postSpy.mockClear();
    vi.stubGlobal('EventSource', FakeEventSource);
    postSpy
      .mockResolvedValueOnce(envelope({ ticket: 'ticket-1' }))
      .mockResolvedValueOnce(envelope({ ticket: 'ticket-2' }));
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    const { unmount } = renderHook(() => useNotificationStream(true), {
      wrapper: wrapperFor(queryClient),
    });

    await vi.advanceTimersByTimeAsync(0);
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(postSpy).toHaveBeenCalledWith('/notifications/stream-ticket');
    const first = FakeEventSource.instances[0];
    expect(first.url).toBe('/api/notifications/stream?ticket=ticket-1');

    first.onerror?.();
    expect(first.closed).toBe(true);
    // No reconnect yet — the first backoff (1s) has not elapsed.
    expect(FakeEventSource.instances).toHaveLength(1);

    await vi.advanceTimersByTimeAsync(NOTIFICATION_STREAM_RECONNECT_BASE_DELAY_MS);
    expect(postSpy).toHaveBeenCalledTimes(2);
    expect(FakeEventSource.instances).toHaveLength(2);
    const second = FakeEventSource.instances[1];
    expect(second.url).toBe('/api/notifications/stream?ticket=ticket-2');

    second.emit('notification');
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: notificationKeys.all });

    unmount();
    expect(second.closed).toBe(true);
  });

  it('stops reconnecting past the cap and falls back to refetch silently (#340)', async () => {
    vi.useFakeTimers();
    postSpy.mockClear();
    vi.stubGlobal('EventSource', FakeEventSource);
    postSpy.mockResolvedValue(envelope({ ticket: 'ticket-cap' }));
    const queryClient = makeClient();

    renderHook(() => useNotificationStream(true), {
      wrapper: wrapperFor(queryClient),
    });

    await vi.advanceTimersByTimeAsync(0);
    expect(FakeEventSource.instances).toHaveLength(1);

    // Three reconnects with exponential backoff (1s, 2s, 4s), then silence.
    const delays = [1, 2, 4].map((n) => n * NOTIFICATION_STREAM_RECONNECT_BASE_DELAY_MS);
    for (const delay of delays) {
      const latest = FakeEventSource.instances[FakeEventSource.instances.length - 1];
      latest.onerror?.();
      expect(latest.closed).toBe(true);
      await vi.advanceTimersByTimeAsync(delay);
      expect(FakeEventSource.instances.length).toBe(postSpy.mock.calls.length);
    }
    expect(FakeEventSource.instances).toHaveLength(1 + MAX_NOTIFICATION_STREAM_RECONNECTS);
    expect(postSpy).toHaveBeenCalledTimes(1 + MAX_NOTIFICATION_STREAM_RECONNECTS);

    // The fourth error is past the cap: no timer, no fifth ticket.
    const last = FakeEventSource.instances[FakeEventSource.instances.length - 1];
    last.onerror?.();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(FakeEventSource.instances).toHaveLength(1 + MAX_NOTIFICATION_STREAM_RECONNECTS);
    expect(postSpy).toHaveBeenCalledTimes(1 + MAX_NOTIFICATION_STREAM_RECONNECTS);
  });

  it('a failed ticket request retries within the same cap (#340)', async () => {
    vi.useFakeTimers();
    postSpy.mockClear();
    vi.stubGlobal('EventSource', FakeEventSource);
    postSpy
      .mockRejectedValueOnce(new Error('ticket failed'))
      .mockResolvedValue(envelope({ ticket: 'ticket-retry' }));
    const queryClient = makeClient();

    renderHook(() => useNotificationStream(true), {
      wrapper: wrapperFor(queryClient),
    });

    await vi.advanceTimersByTimeAsync(0);
    expect(FakeEventSource.instances).toHaveLength(0);

    await vi.advanceTimersByTimeAsync(NOTIFICATION_STREAM_RECONNECT_BASE_DELAY_MS);
    expect(postSpy).toHaveBeenCalledTimes(2);
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(FakeEventSource.instances[0].url).toBe(
      '/api/notifications/stream?ticket=ticket-retry',
    );
  });

  it('unmount cancels a pending reconnect (#340)', async () => {
    vi.useFakeTimers();
    postSpy.mockClear();
    vi.stubGlobal('EventSource', FakeEventSource);
    postSpy.mockResolvedValue(envelope({ ticket: 'ticket-abc' }));
    const queryClient = makeClient();

    const { unmount } = renderHook(() => useNotificationStream(true), {
      wrapper: wrapperFor(queryClient),
    });

    await vi.advanceTimersByTimeAsync(0);
    expect(FakeEventSource.instances).toHaveLength(1);
    const source = FakeEventSource.instances[0];
    source.onerror?.();
    expect(source.closed).toBe(true);

    unmount();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(postSpy).toHaveBeenCalledTimes(1);
  });

  it('pins the second reconnect delay exactly — the backoff is 1s, 2s, 4s, not shorter (#388)', async () => {
    // #347 survivor: the delay arithmetic `* 2 ** (nextAttempt - 1)`
    // -> `/` survived because the cap test advances the full nominal
    // delay each time, so a shorter mutant delay still lands inside
    // the advance. Advance 1ms short of the second delay and assert
    // no reconnect yet, then the last millisecond opens it.
    vi.useFakeTimers();
    postSpy.mockClear();
    vi.stubGlobal('EventSource', FakeEventSource);
    postSpy
      .mockResolvedValueOnce(envelope({ ticket: 'ticket-1' }))
      .mockResolvedValueOnce(envelope({ ticket: 'ticket-2' }))
      .mockResolvedValue(envelope({ ticket: 'ticket-3' }));
    const queryClient = makeClient();

    renderHook(() => useNotificationStream(true), { wrapper: wrapperFor(queryClient) });

    await vi.advanceTimersByTimeAsync(0);
    expect(FakeEventSource.instances).toHaveLength(1);
    const first = FakeEventSource.instances[0];
    first.onerror?.();
    await vi.advanceTimersByTimeAsync(NOTIFICATION_STREAM_RECONNECT_BASE_DELAY_MS);
    expect(postSpy).toHaveBeenCalledTimes(2);
    expect(FakeEventSource.instances).toHaveLength(2);

    const second = FakeEventSource.instances[1];
    second.onerror?.();
    await vi.advanceTimersByTimeAsync(2 * NOTIFICATION_STREAM_RECONNECT_BASE_DELAY_MS - 1);
    expect(postSpy).toHaveBeenCalledTimes(2);
    expect(FakeEventSource.instances).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(postSpy).toHaveBeenCalledTimes(3);
    expect(FakeEventSource.instances).toHaveLength(3);
  });

  it('a failed ticket request retries with the exact backoff and stops at the cap (#388)', async () => {
    // #347 survivor: the catch-path retry step `attempt + 1` ->
    // `attempt - 1` survived because the single retry test asserts a
    // retry happens, never the attempt value. The mutant's first
    // retry lands at 250ms (1000 / 2 ** 2), so pin the 1s boundary
    // first, then count the retries through the ladder to the cap.
    vi.useFakeTimers();
    postSpy.mockClear();
    vi.stubGlobal('EventSource', FakeEventSource);
    postSpy.mockRejectedValue(new Error('ticket failed'));
    const queryClient = makeClient();

    renderHook(() => useNotificationStream(true), { wrapper: wrapperFor(queryClient) });

    await vi.advanceTimersByTimeAsync(0);
    expect(postSpy).toHaveBeenCalledTimes(1);
    expect(FakeEventSource.instances).toHaveLength(0);

    await vi.advanceTimersByTimeAsync(250);
    expect(postSpy).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(750);
    expect(postSpy).toHaveBeenCalledTimes(2);

    await vi.advanceTimersByTimeAsync(2 * NOTIFICATION_STREAM_RECONNECT_BASE_DELAY_MS);
    expect(postSpy).toHaveBeenCalledTimes(3);
    await vi.advanceTimersByTimeAsync(4 * NOTIFICATION_STREAM_RECONNECT_BASE_DELAY_MS);
    expect(postSpy).toHaveBeenCalledTimes(4);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(postSpy).toHaveBeenCalledTimes(4);
    expect(FakeEventSource.instances).toHaveLength(0);
  });

  it('does not open the stream when EventSource is unavailable, even while enabled (#388)', async () => {
    // #347 survivors: the `typeof EventSource === 'undefined'` guard
    // (StringLiteral -> "" and ConditionalExpression -> false) survived
    // because every stream test runs with EventSource stubbed in. The
    // disabled test cannot kill them — `!enabled` short-circuits the
    // `||` before the mutated side. Remove EventSource and assert no
    // ticket request while enabled.
    vi.useFakeTimers();
    postSpy.mockClear();
    vi.stubGlobal('EventSource', undefined);
    // Base implementation, deliberately not mockRejectedValueOnce:
    // on the original code no ticket request ever happens, so a Once
    // would stay queued and be consumed by the NEXT test's first
    // POST instead (#388 leak — it silently vacated the
    // unmount-before-ticket test's deferred ticket).
    postSpy.mockRejectedValue(new Error('ticket unavailable'));
    const queryClient = makeClient();

    renderHook(() => useNotificationStream(true), { wrapper: wrapperFor(queryClient) });

    await vi.advanceTimersByTimeAsync(0);
    expect(postSpy).not.toHaveBeenCalled();
    expect(FakeEventSource.instances).toHaveLength(0);
  });

  it('unmount before the ticket resolves opens no stream (#388)', async () => {
    // #347 survivors: the cleanup `cancelled = true` (BooleanLiteral
    // -> false) and the ticket-resolution cancelled guard
    // (ConditionalExpression -> false, BlockStatement -> {}) survived
    // because no test races cancellation against the ticket
    // resolution. Resolve the ticket after unmount and assert the
    // stream never opens.
    vi.useFakeTimers();
    postSpy.mockClear();
    vi.stubGlobal('EventSource', FakeEventSource);
    let resolveTicket!: (value: unknown) => void;
    const ticketPromise = new Promise((resolve) => {
      resolveTicket = resolve;
    });
    // mockReset first: an unconsumed Once queued by an earlier test
    // would be consumed by this POST instead of the deferred ticket
    // below, and the .then under test would never run.
    postSpy.mockReset();
    postSpy.mockImplementationOnce(() => ticketPromise as never);
    const queryClient = makeClient();

    const { unmount } = renderHook(() => useNotificationStream(true), {
      wrapper: wrapperFor(queryClient),
    });

    await vi.advanceTimersByTimeAsync(0);
    expect(postSpy).toHaveBeenCalledTimes(1);
    expect(FakeEventSource.instances).toHaveLength(0);

    unmount();
    resolveTicket(envelope({ ticket: 'ticket-late' }));
    await vi.advanceTimersByTimeAsync(0);
    expect(FakeEventSource.instances).toHaveLength(0);
    expect(postSpy).toHaveBeenCalledTimes(1);
  });

  it('a stale second error on a torn-down source is a no-op, not a crash (#388)', async () => {
    // #347 survivor: the onerror `source?.close()` (OptionalChaining
    // -> `source.close`) survived because the source is non-null on
    // the first error. After the first error the source is nulled, so
    // a second (stale) error event on the same instance must not
    // throw — the `?.` is the defence for exactly this.
    vi.useFakeTimers();
    postSpy.mockClear();
    vi.stubGlobal('EventSource', FakeEventSource);
    postSpy.mockResolvedValue(envelope({ ticket: 'ticket-abc' }));
    const queryClient = makeClient();

    const { unmount } = renderHook(() => useNotificationStream(true), {
      wrapper: wrapperFor(queryClient),
    });

    await vi.advanceTimersByTimeAsync(0);
    expect(FakeEventSource.instances).toHaveLength(1);
    const first = FakeEventSource.instances[0];
    first.onerror?.();
    expect(first.closed).toBe(true);
    expect(() => first.onerror?.()).not.toThrow();
    // No reconnect yet — both timers (the leaked first one and the
    // re-scheduled one) fire at the 1s backoff.
    expect(postSpy).toHaveBeenCalledTimes(1);
    expect(FakeEventSource.instances).toHaveLength(1);

    unmount();
  });

  it('a leaked reconnect timer must not reopen the stream after unmount (#388)', async () => {
    // #347 survivors: the openStream-entry cancelled guard
    // (ConditionalExpression -> false, BlockStatement -> {}) survived
    // because no test races cancellation against the openStream entry.
    // The stale second error re-schedules a reconnect and overwrites
    // reconnectTimer, so the first timer is leaked past cleanup and
    // only the entry guard stops it from reopening the stream.
    vi.useFakeTimers();
    postSpy.mockClear();
    vi.stubGlobal('EventSource', FakeEventSource);
    postSpy.mockResolvedValue(envelope({ ticket: 'ticket-abc' }));
    const queryClient = makeClient();

    const { unmount } = renderHook(() => useNotificationStream(true), {
      wrapper: wrapperFor(queryClient),
    });

    await vi.advanceTimersByTimeAsync(0);
    expect(FakeEventSource.instances).toHaveLength(1);
    const first = FakeEventSource.instances[0];
    first.onerror?.();
    first.onerror?.();

    unmount();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(postSpy).toHaveBeenCalledTimes(1);
  });

  it('unmount with a pending reconnect clears the timer (#388)', async () => {
    // #347 survivors: the cleanup `reconnectTimer !== null` branch
    // (ConditionalExpression -> false, EqualityOperator -> ===,
    // BlockStatement -> {}) and the `clearTimeout` call
    // (CallExpression -> ;) survived because the two cleanup steps
    // are redundant defences — mutating either one alone still
    // suppresses the reconnect through the cancelled guard, so the
    // unmount test cannot tell which step ran. The pending-timer
    // count is the observable that separates them.
    vi.useFakeTimers();
    postSpy.mockClear();
    vi.stubGlobal('EventSource', FakeEventSource);
    postSpy.mockResolvedValue(envelope({ ticket: 'ticket-abc' }));
    const queryClient = makeClient();

    const { unmount } = renderHook(() => useNotificationStream(true), {
      wrapper: wrapperFor(queryClient),
    });

    await vi.advanceTimersByTimeAsync(0);
    expect(FakeEventSource.instances).toHaveLength(1);
    const source = FakeEventSource.instances[0];
    source.onerror?.();
    expect(source.closed).toBe(true);

    const pendingBefore = vi.getTimerCount();
    expect(pendingBefore).toBeGreaterThanOrEqual(1);
    unmount();
    expect(vi.getTimerCount()).toBe(pendingBefore - 1);
    expect(source.closed).toBe(true);

    await vi.advanceTimersByTimeAsync(60_000);
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(postSpy).toHaveBeenCalledTimes(1);
  });

  it('re-rendering with enabled flipping re-opens and closes the stream (#388)', async () => {
    // #347 survivor: the effect dependency array (ArrayDeclaration
    // -> []) survived because no test re-renders with changed
    // `enabled`/`queryClient`. Flip both ways and assert the stream
    // follows.
    vi.useFakeTimers();
    postSpy.mockClear();
    vi.stubGlobal('EventSource', FakeEventSource);
    postSpy.mockResolvedValue(envelope({ ticket: 'ticket-abc' }));
    const queryClient = makeClient();

    const { rerender, unmount } = renderHook(
      ({ enabled }: { enabled: boolean }) => useNotificationStream(enabled),
      { initialProps: { enabled: false }, wrapper: wrapperFor(queryClient) },
    );

    await vi.advanceTimersByTimeAsync(0);
    expect(postSpy).not.toHaveBeenCalled();
    expect(FakeEventSource.instances).toHaveLength(0);

    rerender({ enabled: true });
    await vi.advanceTimersByTimeAsync(0);
    expect(postSpy).toHaveBeenCalledWith('/notifications/stream-ticket');
    expect(FakeEventSource.instances).toHaveLength(1);
    const source = FakeEventSource.instances[0];

    rerender({ enabled: false });
    await vi.advanceTimersByTimeAsync(0);
    expect(source.closed).toBe(true);

    unmount();
  });

  it('the stream opens by default with no argument (#388)', async () => {
    // #347 no-coverage: the default `enabled = true` param
    // (BooleanLiteral -> false) survived because every stream test
    // passes an explicit arg. Render with no argument and assert the
    // stream opens.
    vi.stubGlobal('EventSource', FakeEventSource);
    postSpy.mockResolvedValue(envelope({ ticket: 'ticket-abc' }));
    const queryClient = makeClient();

    renderHook(() => useNotificationStream(), { wrapper: wrapperFor(queryClient) });

    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));
    expect(postSpy).toHaveBeenCalledWith('/notifications/stream-ticket');
  });
});
