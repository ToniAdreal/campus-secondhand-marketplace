import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { ApiResponse, Page } from './items';

export interface Message {
  id: number;
  itemId: number;
  senderId: number;
  receiverId: number;
  body: string;
  createdAt: string;
  /** When the receiver opened the thread; null while unread (#106). */
  readAt: string | null;
}

export const messageKeys = {
  all: ['messages'] as const,
  thread: (itemId: number) => [...messageKeys.all, 'thread', itemId] as const,
  unreadCount: ['messages', 'unread-count'] as const,
};

/**
 * GET /api/messages?itemId= — the caller's conversation thread for a
 * listing, as an infinite query. The backend paginates the thread: page 0
 * is the newest page (default 20, capped at 50), messages are
 * chronological within the page, so older pages are numerically higher
 * and get prepended by the UI. The backend answers 403 (not an empty
 * list) when the caller never participated, so a third party cannot tell
 * "no conversation" from "none of your business"; callers render that
 * 403 as a privacy note, never the thread.
 */
export function useMessageThread(itemId: number) {
  const queryClient = useQueryClient();
  return useInfiniteQuery({
    queryKey: messageKeys.thread(itemId),
    queryFn: ({ pageParam = 0 }) =>
      api
        .get<ApiResponse<Page<Message>>>('/messages', { params: { itemId, page: pageParam } })
        .then((res) => res.data.data),
    getNextPageParam: (lastPage) =>
      lastPage.number + 1 < lastPage.totalPages ? lastPage.number + 1 : undefined,
    // Opening a thread marks the caller's received rows read on the
    // backend (#106), so the nav unread badge must refetch its count.
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: messageKeys.unreadCount });
    },
  });
}

/**
 * GET /api/messages/unread-count — the caller's total unread messages
 * across all listings, for the nav badge (#106). A bare count: the
 * endpoint exposes no message bodies or sender data.
 */
export function useUnreadMessageCount(enabled = true) {
  return useQuery({
    queryKey: messageKeys.unreadCount,
    queryFn: () =>
      api
        .get<ApiResponse<number>>('/messages/unread-count')
        .then((res) => res.data.data),
    enabled,
  });
}

/**
 * Stitches the paged thread oldest-first, deduped by id. Deduping
 * matters because sending a message invalidates the thread and every
 * fetched page is refetched: the new message shifts one row from page 0
 * onto page 1, so the same message would otherwise appear twice.
 */
export function stitchThreadPages(pages: Page<Message>[]): Message[] {
  const seen = new Set<number>();
  const stitched: Message[] = [];
  for (let i = pages.length - 1; i >= 0; i--) {
    for (const message of pages[i].content) {
      if (!seen.has(message.id)) {
        seen.add(message.id);
        stitched.push(message);
      }
    }
  }
  return stitched;
}

export interface SendMessageInput {
  itemId: number;
  receiverId: number;
  body: string;
}

/**
 * POST /api/messages. The sender is the JWT principal, never a request
 * field. On success the thread query is invalidated and refetched — the
 * new message's server-assigned id and timestamp cannot be computed
 * locally, so no cache hand-editing (same doctrine as #16).
 */
export function useSendMessage() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: SendMessageInput) =>
      api
        .post<ApiResponse<Message>>('/messages', input)
        .then((res) => res.data.data),
    onSuccess: (_message, input) => {
      void queryClient.invalidateQueries({ queryKey: messageKeys.thread(input.itemId) });
      // A send can be the caller's first step into a thread whose
      // received rows the refetch marks read — refresh the badge (#106).
      void queryClient.invalidateQueries({ queryKey: messageKeys.unreadCount });
    },
  });
}

/**
 * Maps a failed POST /api/messages to a user-facing message, keeping the
 * raw envelope text out of the UI like describeOrderError does.
 */
export function describeMessageError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 422 || /yourself/i.test(message)) {
    return 'You cannot message yourself.';
  }
  if (code === 400 || /blank|too long/i.test(message)) {
    return 'Your message is empty or too long (2000 characters max).';
  }
  if (code === 403) {
    return 'You can only message the seller, or reply inside an existing conversation.';
  }
  if (code === 404) {
    return 'This listing no longer exists.';
  }
  return message || 'Something went wrong. Please try again.';
}
