import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import type { Message } from '../api/messages';
import { useAuthStore } from '../store/useAuthStore';
import MessageThread from './MessageThread';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');

const buyer: AuthUser = { id: 5, username: 'buyer', email: 'buyer@example.com', roles: ['USER'] };
const seller: AuthUser = { id: 3, username: 'seller', email: 'seller@example.com', roles: ['USER'] };

const msg1: Message = {
  id: 1,
  itemId: 7,
  senderId: 5,
  receiverId: 3,
  body: 'Is this still available?',
  createdAt: '2026-10-07T01:00:00Z',
  readAt: null,
};
const msg2: Message = {
  id: 2,
  itemId: 7,
  senderId: 3,
  receiverId: 5,
  body: 'Yes — pickup this weekend works.',
  createdAt: '2026-10-07T02:00:00Z',
  readAt: null,
};

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function pageEnvelope(messages: Message[], page = 0, totalPages = 1) {
  return envelope({
    content: messages,
    totalElements: messages.length,
    totalPages,
    number: page,
  });
}

function axiosFailure(status: number, message: string) {
  return {
    isAxiosError: true,
    response: { status, data: { code: status, message, data: null } },
  };
}

function renderThread() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MessageThread itemId={7} sellerId={3} />
    </QueryClientProvider>,
  );
}

describe('MessageThread', () => {
  beforeEach(() => {
    // synchronous reset — store logout() now calls the server (would pollute spies)
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
  });

  it('renders the thread chronologically with You/Seller labels', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    getSpy.mockResolvedValueOnce(pageEnvelope([msg1, msg2]));
    renderThread();

    await waitFor(() => expect(screen.getByText('Is this still available?')).toBeTruthy());
    expect(screen.getByText('Yes — pickup this weekend works.')).toBeTruthy();
    expect(screen.getByText('You')).toBeTruthy();
    expect(screen.getByText('Seller')).toBeTruthy();
    expect(getSpy).toHaveBeenCalledWith('/messages', { params: { itemId: 7, page: 0 } });
  });

  it('sending posts to the seller and the new message arrives via thread refetch', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    const msg3: Message = {
      id: 3,
      itemId: 7,
      senderId: 5,
      receiverId: 3,
      body: 'Great, see you then!',
      createdAt: '2026-10-07T03:00:00Z',
      readAt: null,
    };
    getSpy.mockResolvedValueOnce(pageEnvelope([msg1, msg2])); // initial thread load
    getSpy.mockResolvedValueOnce(pageEnvelope([msg1, msg2, msg3])); // refetch after send
    postSpy.mockResolvedValueOnce(envelope(msg3));
    renderThread();

    await waitFor(() => expect(screen.getByText('Is this still available?')).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/message the seller/i), {
      target: { value: 'Great, see you then!' },
    });
    fireEvent.click(screen.getByRole('button', { name: /send/i }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(1));
    expect(postSpy).toHaveBeenCalledWith('/messages', {
      itemId: 7,
      receiverId: 3,
      body: 'Great, see you then!',
    });

    // The mutation invalidates the thread query — the new message arrives
    // through a refetch, never a manual cache edit.
    await waitFor(() => expect(screen.getByText('Great, see you then!')).toBeTruthy());
    expect(getSpy).toHaveBeenCalledTimes(2);

    // The draft clears after a successful send.
    expect(
      (screen.getByLabelText(/message the seller/i) as HTMLTextAreaElement).value,
    ).toBe('');
  });

  it('a 403 hides the thread behind a privacy note, never the raw message', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    getSpy.mockRejectedValueOnce(axiosFailure(403, 'not a participant in this conversation'));
    renderThread();

    await waitFor(() => expect(screen.getByText(/private/i)).toBeTruthy());
    expect(screen.getByText(/only participants can see it/i)).toBeTruthy();
    expect(screen.queryByText('Is this still available?')).toBeNull();
    // The raw envelope message never leaks to the UI.
    expect(screen.queryByText(/not a participant in this conversation/)).toBeNull();
    // The send box stays: messaging the seller is how a buyer joins the thread.
    expect(screen.getByLabelText(/message the seller/i)).toBeTruthy();
  });

  it('the seller replies to the buyer who wrote (backlog #112)', async () => {
    useAuthStore.getState().login(seller, 'tok');
    const reply: Message = {
      id: 3,
      itemId: 7,
      senderId: 3,
      receiverId: 5,
      body: 'Yes — pickup this weekend works.',
      createdAt: '2026-10-07T03:00:00Z',
      readAt: null,
    };
    getSpy.mockResolvedValueOnce(pageEnvelope([msg1])); // initial thread load
    getSpy.mockResolvedValueOnce(pageEnvelope([msg1, reply])); // refetch after send
    postSpy.mockResolvedValueOnce(envelope(reply));
    renderThread();

    await waitFor(() => expect(screen.getByText('Is this still available?')).toBeTruthy());
    // No self-addressed box: the reply box names the buyer from the thread.
    expect(screen.queryByLabelText(/message the seller/i)).toBeNull();
    expect(screen.getByLabelText(/reply to user #5/i)).toBeTruthy();

    fireEvent.change(screen.getByLabelText(/reply to user #5/i), {
      target: { value: 'Yes — pickup this weekend works.' },
    });
    fireEvent.click(screen.getByRole('button', { name: /send/i }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(1));
    expect(postSpy).toHaveBeenCalledWith('/messages', {
      itemId: 7,
      receiverId: 5,
      body: 'Yes — pickup this weekend works.',
    });
    await waitFor(() =>
      expect(screen.getByText('Yes — pickup this weekend works.')).toBeTruthy(),
    );
    expect(
      (screen.getByLabelText(/reply to user #5/i) as HTMLTextAreaElement).value,
    ).toBe('');
  });

  it('the seller picks which buyer thread to answer when several buyers wrote', async () => {
    useAuthStore.getState().login(seller, 'tok');
    const otherBuyerMsg: Message = {
      id: 4,
      itemId: 7,
      senderId: 9,
      receiverId: 3,
      body: 'Would you take 100?',
      createdAt: '2026-10-07T04:00:00Z',
      readAt: null,
    };
    const replyToNine: Message = {
      id: 5,
      itemId: 7,
      senderId: 3,
      receiverId: 9,
      body: '110 is my floor.',
      createdAt: '2026-10-07T05:00:00Z',
      readAt: null,
    };
    getSpy.mockResolvedValueOnce(pageEnvelope([msg1, otherBuyerMsg]));
    getSpy.mockResolvedValueOnce(pageEnvelope([msg1, otherBuyerMsg, replyToNine]));
    postSpy.mockResolvedValueOnce(envelope(replyToNine));
    renderThread();

    // Default view is the first buyer's thread only — no merged mega-thread.
    await waitFor(() => expect(screen.getByText('Is this still available?')).toBeTruthy());
    expect(screen.queryByText('Would you take 100?')).toBeNull();
    expect(screen.getByLabelText(/reply to user #5/i)).toBeTruthy();

    // Switch to the second buyer's thread.
    fireEvent.click(screen.getByRole('button', { name: 'User #9' }));
    await waitFor(() => expect(screen.getByText('Would you take 100?')).toBeTruthy());
    expect(screen.queryByText('Is this still available?')).toBeNull();
    expect(screen.getByLabelText(/reply to user #9/i)).toBeTruthy();

    fireEvent.change(screen.getByLabelText(/reply to user #9/i), {
      target: { value: '110 is my floor.' },
    });
    fireEvent.click(screen.getByRole('button', { name: /send/i }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(1));
    expect(postSpy).toHaveBeenCalledWith('/messages', {
      itemId: 7,
      receiverId: 9,
      body: '110 is my floor.',
    });
  });

  it('a failed send shows a friendly error and keeps the draft', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    getSpy.mockResolvedValueOnce(pageEnvelope([msg1, msg2]));
    postSpy.mockRejectedValueOnce(axiosFailure(422, 'cannot send a message to yourself'));
    renderThread();

    await waitFor(() => expect(screen.getByText('Is this still available?')).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/message the seller/i), {
      target: { value: 'hello?' },
    });
    fireEvent.click(screen.getByRole('button', { name: /send/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toBe('You cannot message yourself.');
    // The draft is preserved so the user can fix and retry.
    expect(
      (screen.getByLabelText(/message the seller/i) as HTMLTextAreaElement).value,
    ).toBe('hello?');
  });

  it('"Load earlier messages" stitches older pages on top; the button disappears on the last page', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    // page 0 is the newest page; page 1 holds the older messages
    getSpy.mockResolvedValueOnce(pageEnvelope([msg2], 0, 2)); // initial newest page
    getSpy.mockResolvedValueOnce(pageEnvelope([msg1], 1, 2)); // earlier page
    renderThread();

    await waitFor(() => expect(screen.getByText('Yes — pickup this weekend works.')).toBeTruthy());
    // a second page exists, so the button is shown
    const loadButton = screen.getByRole('button', { name: /load earlier messages/i });
    expect(loadButton).toBeTruthy();

    fireEvent.click(loadButton);
    await waitFor(() => expect(screen.getByText('Is this still available?')).toBeTruthy());
    expect(getSpy).toHaveBeenCalledWith('/messages', { params: { itemId: 7, page: 1 } });

    // older page stitched above the newest one — chronological overall,
    // newest page still anchored at the bottom
    const items = screen.getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0].textContent).toMatch(/Is this still available/);
    expect(items[1].textContent).toMatch(/Yes — pickup this weekend works/);

    // last page reached — the button disappears
    expect(screen.queryByRole('button', { name: /load earlier messages/i })).toBeNull();
  });

  it('sending while older pages are loaded does not duplicate shifted messages', async () => {
    useAuthStore.getState().login(buyer, 'tok');
    const msg3: Message = {
      id: 3,
      itemId: 7,
      senderId: 5,
      receiverId: 3,
      body: 'Great, see you then!',
      createdAt: '2026-10-07T03:00:00Z',
      readAt: null,
    };
    getSpy.mockResolvedValueOnce(pageEnvelope([msg2], 0, 2)); // initial newest page
    getSpy.mockResolvedValueOnce(pageEnvelope([msg1], 1, 2)); // load earlier
    // after the send, the refetch shifts one row: msg2 moves onto page 1
    getSpy.mockResolvedValueOnce(pageEnvelope([msg2, msg3], 0, 2)); // refetched page 0
    getSpy.mockResolvedValueOnce(pageEnvelope([msg1, msg2], 1, 2)); // refetched page 1
    postSpy.mockResolvedValueOnce(envelope(msg3));
    renderThread();

    await waitFor(() => expect(screen.getByText('Yes — pickup this weekend works.')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: /load earlier messages/i }));
    await waitFor(() => expect(screen.getByText('Is this still available?')).toBeTruthy());

    fireEvent.change(screen.getByLabelText(/message the seller/i), {
      target: { value: 'Great, see you then!' },
    });
    fireEvent.click(screen.getByRole('button', { name: /send/i }));

    // the shifted msg2 appears in both refetched pages — it must render once
    await waitFor(() => expect(screen.getByText('Great, see you then!')).toBeTruthy());
    expect(screen.getAllByText('Yes — pickup this weekend works.')).toHaveLength(1);
    expect(getSpy).toHaveBeenCalledTimes(4);
  });
});
