import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import type { Message } from '../api/messages';
import type { Page } from '../api/items';
import { useAuthStore } from '../store/useAuthStore';
import MessageSearchPage from './MessageSearchPage';

const getSpy = vi.spyOn(api, 'get');

const user: AuthUser = { id: 5, username: 'buyer', email: 'buyer@example.com', roles: ['USER'] };

function msg(id: number, itemId: number, body: string): Message {
  return {
    id,
    itemId,
    senderId: 5,
    receiverId: 3,
    body,
    createdAt: '2026-10-09T08:00:00Z',
    readAt: null,
    editedAt: null,
    deletedAt: null,
    photoUrl: null,
  };
}

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function pageEnvelope(content: Message[], number: number, totalPages: number) {
  const page: Page<Message> = { content, totalElements: content.length, totalPages, number };
  return envelope(page);
}

function envelopeFailure(code: number, message: string) {
  return { response: { data: { code, message, data: null } } };
}

function renderAt(initialEntry: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/messages" element={<MessageSearchPage />} />
          <Route path="/items/:id" element={<div>item detail stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('MessageSearchPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('blank q shows the empty state and fires no search request', async () => {
    useAuthStore.getState().login(user, 'tok');
    renderAt('/messages');
    expect(
      screen.getByText('Type a word above to search your messages across all conversations.'),
    ).toBeTruthy();
    expect(getSpy).not.toHaveBeenCalledWith(
      '/messages/search',
      expect.objectContaining({ params: expect.objectContaining({ q: '' }) }),
    );
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('a match renders with a link back to the owning item thread', async () => {
    useAuthStore.getState().login(user, 'tok');
    const hit = msg(7, 42, 'does the blue bike lock come with a key');
    getSpy.mockResolvedValue(pageEnvelope([hit], 0, 1));

    renderAt('/messages?q=bike');
    await waitFor(() =>
      expect(screen.getByText('does the blue bike lock come with a key')).toBeTruthy(),
    );

    const link = screen.getByRole('link', { name: 'View conversation' });
    expect(link.getAttribute('href')).toBe('/items/42');
    expect(getSpy).toHaveBeenCalledWith('/messages/search', {
      params: { q: 'bike', page: 0, size: 20 },
    });
  });

  it('a 401 renders the describeMessageError fallback copy', async () => {
    useAuthStore.getState().login(user, 'tok');
    getSpy.mockRejectedValue(envelopeFailure(401, ''));

    renderAt('/messages?q=bike');
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveProperty(
        'textContent',
        'Something went wrong. Please try again.',
      ),
    );
  });

  it('a 403 renders the describeMessageError participant copy', async () => {
    useAuthStore.getState().login(user, 'tok');
    getSpy.mockRejectedValue(envelopeFailure(403, 'not a participant'));

    renderAt('/messages?q=bike');
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveProperty(
        'textContent',
        'You can only message the seller, or reply inside an existing conversation.',
      ),
    );
  });

  it('pagination appends the next page instead of replacing', async () => {
    useAuthStore.getState().login(user, 'tok');
    const first = msg(1, 42, 'searchable note one');
    const second = msg(2, 43, 'searchable note two');
    getSpy.mockImplementation((_url: string, config?: { params?: { page?: number } }) => {
      if (config?.params?.page === 1) {
        return Promise.resolve(pageEnvelope([second], 1, 2));
      }
      return Promise.resolve(pageEnvelope([first], 0, 2));
    });

    renderAt('/messages?q=searchable');
    await waitFor(() => expect(screen.getByText('searchable note one')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    await waitFor(() => expect(screen.getByText('searchable note two')).toBeTruthy());
    // The first page stays on screen: append, not replace.
    expect(screen.getByText('searchable note one')).toBeTruthy();
    expect(getSpy).toHaveBeenCalledWith('/messages/search', {
      params: { q: 'searchable', page: 1, size: 20 },
    });
  });
});
