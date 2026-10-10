import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { lazy, type ComponentType } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import { api, setAccessToken } from './api/client';
import { useAuthStore } from './store/useAuthStore';

const getSpy = vi.spyOn(api, 'get');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function renderApp(path = '/') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/" element={<App />}>
            <Route index element={<div>home content</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// Route pages are React.lazy chunks (see main.tsx). While a chunk is
// still loading, App's outlet must show the single Suspense fallback
// inside the app shell — not a blank page — and swap in the page once
// the chunk resolves.
describe('App Suspense fallback for lazy routes', () => {
  it('renders the Loading… fallback while a route chunk resolves, then the page', async () => {
    let resolveChunk!: (module: { default: ComponentType }) => void;
    const chunk = new Promise<{ default: ComponentType }>((resolve) => {
      resolveChunk = resolve;
    });
    const SlowPage = lazy(() => chunk);
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/slow']}>
          <Routes>
            <Route path="/" element={<App />}>
              <Route path="slow" element={<SlowPage />} />
            </Route>
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    // The fallback is up, inside the shell (header brand link present),
    // and the page content is not there yet.
    expect(screen.getByRole('status').textContent).toBe('Loading…');
    expect(screen.getByRole('link', { name: 'Campus Marketplace' })).toBeTruthy();
    expect(screen.queryByText('slow page content')).toBeNull();

    await act(async () => {
      resolveChunk({ default: () => <div>slow page content</div> });
    });

    expect(await screen.findByText('slow page content')).toBeTruthy();
    expect(screen.queryByRole('status')).toBeNull();

    cleanup();
  });
});

describe('App nav unread-message badge (backlog #106)', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  it('renders the badge with the unread count when it is above zero', async () => {
    useAuthStore.getState().login(
      { id: 5, username: 'buyer', email: 'buyer@example.com', roles: ['USER'] },
      'tok',
    );
    getSpy.mockResolvedValue(envelope(3));
    renderApp();

    await waitFor(() =>
      expect(screen.getByLabelText('3 unread messages')).toBeTruthy(),
    );
    expect(screen.getByText('3 unread')).toBeTruthy();
    expect(getSpy).toHaveBeenCalledWith('/messages/unread-count');
  });

  it('hides the badge at zero', async () => {
    useAuthStore.getState().login(
      { id: 5, username: 'buyer', email: 'buyer@example.com', roles: ['USER'] },
      'tok',
    );
    getSpy.mockResolvedValue(envelope(0));
    renderApp();

    await waitFor(() => expect(screen.getByText('buyer')).toBeTruthy());
    await act(async () => {});
    expect(screen.queryByLabelText(/unread messages/)).toBeNull();
  });

  it('does not query the count while signed out', async () => {
    renderApp();

    await waitFor(() => expect(screen.getByText('home content')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
    expect(screen.queryByLabelText(/unread messages/)).toBeNull();
  });
});
