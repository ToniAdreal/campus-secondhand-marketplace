import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import type { Page } from './items';
import {
  describeMessageError,
  isMessageEditable,
  messageKeys,
  stitchThreadPages,
  useDeleteMessage,
  useEditMessage,
  useMessageThread,
  useSendMessage,
  useUnreadMessageCount,
  type Message,
} from './messages';

const getSpy = vi.spyOn(api, 'get');
const postSpy = vi.spyOn(api, 'post');
const putSpy = vi.spyOn(api, 'put');
const deleteSpy = vi.spyOn(api, 'delete');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function msg(id: number, body = `message ${id}`): Message {
  return {
    id,
    itemId: 42,
    senderId: 5,
    receiverId: 3,
    body,
    createdAt: '2026-10-09T08:00:00Z',
    readAt: null,
    editedAt: null,
    deletedAt: null,
  };
}

function page(content: Message[], number: number, totalPages: number): Page<Message> {
  return { content, totalElements: content.length, totalPages, number };
}

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

// The client is created in the test body and captured by wrapperFor,
// the pattern this suite uses for hook tests that inspect invalidation
// or the cache directly.

function wrapperFor(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

function envelopeError(code: number, message: string) {
  return { response: { data: { code, message } } };
}

describe('useMessageThread', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('requests page 0 with the itemId and unwraps the envelope page', async () => {
    const firstPage = page([msg(3), msg(4)], 0, 2);
    getSpy.mockResolvedValueOnce(envelope(firstPage));

    const { result } = renderHook(() => useMessageThread(42), { wrapper: wrapperFor(makeClient()) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // The hook sends itemId + page only — the size is the backend's
    // default (20, capped at 50); no size param is sent from here.
    expect(getSpy).toHaveBeenCalledWith('/messages', { params: { itemId: 42, page: 0 } });
    expect(result.current.data?.pages).toEqual([firstPage]);
    expect(result.current.hasNextPage).toBe(true);
  });

  it('fetches the next page with page=1 and stops at the last page', async () => {
    getSpy
      .mockResolvedValueOnce(envelope(page([msg(3), msg(4)], 0, 2)))
      .mockResolvedValueOnce(envelope(page([msg(1), msg(2)], 1, 2)));

    const { result, rerender } = renderHook(() => useMessageThread(42), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    await act(async () => {
      await result.current.fetchNextPage();
    });
    // The cache already holds both pages at this point; in this
    // renderHook setup the hook result only republishes on a rerender
    // (the component-level MessageThread tests prove the real UI path
    // updates without one).
    rerender();

    expect(getSpy).toHaveBeenCalledTimes(2);
    expect(getSpy).toHaveBeenLastCalledWith('/messages', { params: { itemId: 42, page: 1 } });
    expect(result.current.data?.pages).toHaveLength(2);
    expect(result.current.hasNextPage).toBe(false);
  });

  it('reports no next page when the thread fits on a single page', async () => {
    getSpy.mockResolvedValueOnce(envelope(page([msg(1)], 0, 1)));

    const { result } = renderHook(() => useMessageThread(42), { wrapper: wrapperFor(makeClient()) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.hasNextPage).toBe(false);
  });

  it('propagates the backend 403 (non-participant) instead of an empty thread', async () => {
    const failure = {
      isAxiosError: true,
      response: { status: 403, data: { code: 403, message: 'not a participant', data: null } },
    };
    getSpy.mockRejectedValueOnce(failure);

    const { result } = renderHook(() => useMessageThread(42), { wrapper: wrapperFor(makeClient()) });
    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(result.current.error).toBe(failure);
    expect(result.current.data).toBeUndefined();
  });
});

describe('stitchThreadPages', () => {
  it('stitches pages oldest-first, preserving chronological order within a page', () => {
    // Pages arrive newest-first: page 0 holds the newest messages.
    const pages = [page([msg(3), msg(4)], 0, 2), page([msg(1), msg(2)], 1, 2)];

    expect(stitchThreadPages(pages).map((m) => m.id)).toEqual([1, 2, 3, 4]);
  });

  it('dedupes a message that shifts onto the next page after a refetch', () => {
    // After sending, page 0 refetches and the boundary message (id 2)
    // appears on both pages; it must render exactly once.
    const pages = [page([msg(2), msg(3)], 0, 2), page([msg(1), msg(2)], 1, 2)];

    const stitched = stitchThreadPages(pages);
    expect(stitched.map((m) => m.id)).toEqual([1, 2, 3]);
  });

  it('returns an empty thread for no pages', () => {
    expect(stitchThreadPages([])).toEqual([]);
  });
});

describe('useSendMessage', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('posts the input, unwraps the created message and invalidates the thread', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    const created = msg(9, 'Is this still available?');
    postSpy.mockResolvedValueOnce(envelope(created));

    const { result } = renderHook(() => useSendMessage(), { wrapper: wrapperFor(queryClient) });
    const returned = await result.current.mutateAsync({
      itemId: 42,
      receiverId: 3,
      body: 'Is this still available?',
    });

    expect(postSpy).toHaveBeenCalledWith('/messages', {
      itemId: 42,
      receiverId: 3,
      body: 'Is this still available?',
    });
    expect(returned).toEqual(created);
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: messageKeys.thread(42) });
  });

  it('does not invalidate the thread when the send fails', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    postSpy.mockRejectedValueOnce(new Error('network down'));

    const { result } = renderHook(() => useSendMessage(), { wrapper: wrapperFor(queryClient) });
    await expect(
      result.current.mutateAsync({ itemId: 42, receiverId: 3, body: 'hi' }),
    ).rejects.toThrow('network down');

    expect(invalidateSpy).not.toHaveBeenCalled();
  });
});

describe('useEditMessage (backlog #206)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('puts the new body, unwraps the edited message and invalidates the thread', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    const edited = { ...msg(9, 'fixed typo'), editedAt: '2026-10-09T08:05:00Z' };
    putSpy.mockResolvedValueOnce(envelope(edited));

    const { result } = renderHook(() => useEditMessage(), { wrapper: wrapperFor(queryClient) });
    const returned = await result.current.mutateAsync({
      itemId: 42,
      messageId: 9,
      body: 'fixed typo',
    });

    expect(putSpy).toHaveBeenCalledWith('/messages/9', { body: 'fixed typo' });
    expect(returned).toEqual(edited);
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: messageKeys.thread(42) });
  });
});

describe('useDeleteMessage (backlog #206)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('deletes, unwraps the tombstone and invalidates thread + unread count', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    const tombstone = {
      ...msg(9, 'This message was deleted'),
      deletedAt: '2026-10-09T08:05:00Z',
    };
    deleteSpy.mockResolvedValueOnce(envelope(tombstone));

    const { result } = renderHook(() => useDeleteMessage(), { wrapper: wrapperFor(queryClient) });
    const returned = await result.current.mutateAsync({ itemId: 42, messageId: 9 });

    expect(deleteSpy).toHaveBeenCalledWith('/messages/9');
    expect(returned).toEqual(tombstone);
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: messageKeys.thread(42) });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: messageKeys.unreadCount });
  });
});

describe('isMessageEditable (backlog #206)', () => {
  const now = Date.parse('2026-10-09T08:10:00Z');

  it('a fresh live message is editable; the window boundary is inclusive', () => {
    expect(isMessageEditable(msg(1), now)).toBe(true);
    const atBoundary = { ...msg(2), createdAt: '2026-10-09T07:55:00Z' };
    expect(isMessageEditable(atBoundary, now)).toBe(true);
  });

  it('a message older than 15 minutes is not editable', () => {
    const old = { ...msg(3), createdAt: '2026-10-09T07:54:59Z' };
    expect(isMessageEditable(old, now)).toBe(false);
  });

  it('a deleted message is never editable', () => {
    const deleted = { ...msg(4), deletedAt: '2026-10-09T08:01:00Z' };
    expect(isMessageEditable(deleted, now)).toBe(false);
  });
});

describe('useUnreadMessageCount', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('fetches the count endpoint and unwraps the envelope number', async () => {
    getSpy.mockResolvedValueOnce(envelope(3));

    const { result } = renderHook(() => useUnreadMessageCount(), {
      wrapper: wrapperFor(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getSpy).toHaveBeenCalledWith('/messages/unread-count');
    expect(result.current.data).toBe(3);
  });

  it('does not fetch while disabled (signed out)', async () => {
    const { result } = renderHook(() => useUnreadMessageCount(false), {
      wrapper: wrapperFor(makeClient()),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('a thread view invalidates the unread-count query (viewing marks read)', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    getSpy.mockResolvedValueOnce(envelope(page([msg(1)], 0, 1)));

    const { result } = renderHook(() => useMessageThread(42), {
      wrapper: wrapperFor(queryClient),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: messageKeys.unreadCount });
  });

  it('a send also invalidates the unread-count query', async () => {
    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    postSpy.mockResolvedValueOnce(envelope(msg(9)));

    const { result } = renderHook(() => useSendMessage(), { wrapper: wrapperFor(queryClient) });
    await result.current.mutateAsync({ itemId: 42, receiverId: 3, body: 'hi' });

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: messageKeys.unreadCount });
  });
});

describe('describeMessageError', () => {
  it.each([
    ['self-message code', envelopeError(422, 'unprocessable'), 'You cannot message yourself.'],
    [
      'self-message wording without a code',
      envelopeError(0, 'you cannot message yourself'),
      'You cannot message yourself.',
    ],
    [
      'blank/too-long code',
      envelopeError(400, 'bad request'),
      'Your message is empty or too long (2000 characters max).',
    ],
    [
      'blank wording without a code',
      envelopeError(0, 'body must not be blank'),
      'Your message is empty or too long (2000 characters max).',
    ],
    ['unknown listing', envelopeError(404, 'item not found'), 'This listing no longer exists.'],
    [
      'send-guard 403 (backlog #112)',
      envelopeError(403, 'not a participant in this conversation'),
      'You can only message the seller, or reply inside an existing conversation.',
    ],
    [
      'edit window closed (backlog #206)',
      envelopeError(422, 'the edit window for this message has closed'),
      'The edit window for this message has closed (15 minutes after sending).',
    ],
    [
      'edit of a deleted message (backlog #206)',
      envelopeError(422, 'a deleted message cannot be edited'),
      'This message was deleted and can no longer be edited.',
    ],
    [
      'non-sender edit/delete (backlog #206)',
      envelopeError(403, 'only the sender can edit this message'),
      'You can only edit or delete your own messages.',
    ],
    ['unknown error keeps the server message', envelopeError(500, 'database unavailable'), 'database unavailable'],
    ['no envelope at all', new Error('boom'), 'Something went wrong. Please try again.'],
    ['empty object', {}, 'Something went wrong. Please try again.'],
  ])('%s', (_label, error, expected) => {
    expect(describeMessageError(error)).toBe(expected);
  });
});
