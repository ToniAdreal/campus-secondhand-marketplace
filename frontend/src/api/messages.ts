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
  /** When the sender last edited the message; null while never edited (#206). */
  editedAt: string | null;
  /**
   * When the sender soft-deleted the message; null while live (#206).
   * A deleted message's body is the backend tombstone text — the
   * original wording no longer exists on the server.
   */
  deletedAt: string | null;
  /**
   * Public path ("/uploads/<uuid>.<ext>") of the message's one optional
   * photo attachment (backlog #245); null when the message carries no
   * photo — including a soft-deleted message, whose attachment the
   * backend clears at delete time together with its body.
   */
  photoUrl: string | null;
}

/** The backend edit window (app.messages.edit-window, #206): 15 minutes from send. */
export const MESSAGE_EDIT_WINDOW_MS = 15 * 60 * 1000;

/** Whether a message the caller sent can still be edited right now (#206). */
export function isMessageEditable(message: Message, nowMs: number = Date.now()): boolean {
  if (message.deletedAt !== null) return false;
  return nowMs - Date.parse(message.createdAt) <= MESSAGE_EDIT_WINDOW_MS;
}

export const messageKeys = {
  all: ['messages'] as const,
  thread: (itemId: number) => [...messageKeys.all, 'thread', itemId] as const,
  unreadCount: ['messages', 'unread-count'] as const,
  search: (q: string) => [...messageKeys.all, 'search', q] as const,
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
 * GET /api/messages/search — cross-thread search over the caller's own
 * threads (backlog #260, surfaced by #273). Sent + received, body text
 * only, soft-deleted rows excluded, blocked participants NOT filtered
 * (the #260 thread-read rule). Page 0 is the newest page, chronological
 * within the page, like the thread endpoint. A blank query never fires:
 * the backend answers an empty page for it, and the page renders the
 * empty state instead — no request, no error. The size is clamped into
 * the backend's [1, 50] window so a caller can never ask for more than
 * the cap (the default 20 is unchanged).
 */
export function useMessageSearch(q: string, size = 20, enabled = true) {
  const clampedSize = Math.min(50, Math.max(1, size));
  const committed = q.trim();
  return useInfiniteQuery({
    queryKey: messageKeys.search(committed),
    queryFn: ({ pageParam = 0 }) =>
      api
        .get<ApiResponse<Page<Message>>>('/messages/search', {
          params: { q: committed, page: pageParam, size: clampedSize },
        })
        .then((res) => res.data.data),
    getNextPageParam: (lastPage) =>
      lastPage.number + 1 < lastPage.totalPages ? lastPage.number + 1 : undefined,
    enabled: enabled && committed !== '',
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
  /** One optional photo attachment (backlog #245); without it the send stays JSON. */
  photo?: File;
}

/**
 * POST /api/messages, or POST /api/messages/photo (multipart) when a
 * photo is attached (backlog #245). The sender is the JWT principal,
 * never a request field. The photo branch builds FormData and lets
 * Axios set the multipart Content-Type + boundary itself, like the
 * listing-photo upload; the JSON branch is byte-identical to before,
 * so a photo-less message posts exactly what it always posted. On
 * success the thread query is invalidated and refetched — the new
 * message's server-assigned id and timestamp cannot be computed
 * locally, so no cache hand-editing (same doctrine as #16).
 */
export function useSendMessage() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: SendMessageInput) => {
      if (input.photo) {
        const form = new FormData();
        form.append('itemId', String(input.itemId));
        form.append('receiverId', String(input.receiverId));
        form.append('body', input.body);
        form.append('file', input.photo, input.photo.name);
        return api
          .post<ApiResponse<Message>>('/messages/photo', form)
          .then((res) => res.data.data);
      }
      return api
        .post<ApiResponse<Message>>('/messages', input)
        .then((res) => res.data.data);
    },
    onSuccess: (_message, input) => {
      void queryClient.invalidateQueries({ queryKey: messageKeys.thread(input.itemId) });
      // A send can be the caller's first step into a thread whose
      // received rows the refetch marks read — refresh the badge (#106).
      void queryClient.invalidateQueries({ queryKey: messageKeys.unreadCount });
    },
  });
}

export interface EditMessageInput {
  itemId: number;
  messageId: number;
  body: string;
}

/**
 * PUT /api/messages/{id} (backlog #206). Sender-only, inside the
 * 15-minute edit window; the backend answers 422 outside it. On
 * success the thread query is invalidated and refetched — the
 * server-assigned editedAt cannot be computed locally, so no cache
 * hand-editing (same doctrine as the send, #16).
 */
export function useEditMessage() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: EditMessageInput) =>
      api
        .put<ApiResponse<Message>>(`/messages/${input.messageId}`, { body: input.body })
        .then((res) => res.data.data),
    onSuccess: (_message, input) => {
      void queryClient.invalidateQueries({ queryKey: messageKeys.thread(input.itemId) });
    },
  });
}

export interface DeleteMessageInput {
  itemId: number;
  messageId: number;
}

/**
 * DELETE /api/messages/{id} (backlog #206). Sender-only soft delete:
 * the row stays in the thread as a tombstone and stops counting as
 * unread, so both the thread and the unread-count queries are
 * invalidated on success (same doctrine as the send, #16).
 */
export function useDeleteMessage() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: DeleteMessageInput) =>
      api
        .delete<ApiResponse<Message>>(`/messages/${input.messageId}`)
        .then((res) => res.data.data),
    onSuccess: (_message, input) => {
      void queryClient.invalidateQueries({ queryKey: messageKeys.thread(input.itemId) });
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
  // Edit/delete failures first (backlog #206): their 422/403 codes
  // overlap the send's, so the specific wording is matched before the
  // generic code rules below.
  if (/edit window/i.test(message)) {
    return 'The edit window for this message has closed (15 minutes after sending).';
  }
  if (/deleted message cannot be edited/i.test(message)) {
    return 'This message was deleted and can no longer be edited.';
  }
  if (/only the sender/i.test(message)) {
    return 'You can only edit or delete your own messages.';
  }
  if (code === 422 || /yourself/i.test(message)) {
    return 'You cannot message yourself.';
  }
  // Photo-attachment failures (backlog #245): the backend's
  // ImageStorageService wording (unsupported type, size cap, magic
  // mismatch, malformed content) maps to one friendly line — the raw
  // type string or byte detail never reaches the UI.
  if (
    /unsupported image|image too large|image content does not match|malformed image/i.test(
      message,
    )
  ) {
    return 'That photo could not be attached — use a PNG, JPEG, WebP or GIF under 5 MB.';
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
