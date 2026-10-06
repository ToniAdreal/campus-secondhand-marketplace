import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { api } from '../api/client';
import type { AuthUser } from '../api/auth';
import { useAuthStore } from '../store/useAuthStore';
import CreateListingPage from './CreateListingPage';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');

const fakeUser: AuthUser = { id: 5, username: 'toni', email: 'toni@example.com', roles: ['USER'] };

const fakeCategories = [
  { id: 1, name: 'Electronics', slug: 'electronics' },
  { id: 2, name: 'Books & Study', slug: 'books-study' },
];

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function axiosFailure(status: number, message: string) {
  return {
    isAxiosError: true,
    response: { status, data: { code: status, message, data: null } },
  };
}

function createdItemPayload() {
  return {
    id: 42,
    title: 'Used bike',
    description: null,
    priceCents: 1250,
    status: 'AVAILABLE',
    sellerId: 5,
    categoryId: 1,
    categoryName: 'Electronics',
    photoUrl: null,
  };
}

function createdItem() {
  return envelope(createdItemPayload());
}

let currentPath = '';
function LocationProbe() {
  currentPath = useLocation().pathname;
  return null;
}

function renderCreate(initialEntry = '/items/new') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <LocationProbe />
      <QueryClientProvider client={queryClient}>
        <Routes>
          <Route path="/items/new" element={<CreateListingPage />} />
          <Route path="/login" element={<div>login probe</div>} />
          <Route path="/items/:id" element={<div>detail probe</div>} />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

function fillValidForm() {
  fireEvent.change(screen.getByLabelText(/title/i), { target: { value: 'Used bike' } });
  fireEvent.change(screen.getByLabelText(/price \(usd\)/i), { target: { value: '12.50' } });
}

describe('CreateListingPage', () => {
  beforeEach(() => {
    currentPath = '';
    localStorage.clear();
    useAuthStore.getState().logout();
    getSpy.mockResolvedValue(envelope(fakeCategories));
  });

  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
    useAuthStore.getState().logout();
  });

  it('redirects unauthenticated visitors to /login', async () => {
    renderCreate();
    await waitFor(() => expect(currentPath).toBe('/login'));
    expect(screen.getByText('login probe')).toBeTruthy();
    expect(postSpy).not.toHaveBeenCalled();
  });

  it('blocks a blank title client-side without calling the API', async () => {
    useAuthStore.getState().login(fakeUser, 'tok');
    renderCreate();
    await screen.findByText('Electronics');

    fireEvent.change(screen.getByLabelText(/price \(usd\)/i), { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: /publish listing/i }));

    expect(await screen.findByText('Title is required.')).toBeTruthy();
    expect(postSpy).not.toHaveBeenCalled();
  });

  it('blocks a >200-char title client-side', async () => {
    useAuthStore.getState().login(fakeUser, 'tok');
    renderCreate();
    await screen.findByText('Electronics');

    fireEvent.change(screen.getByLabelText(/title/i), { target: { value: 'x'.repeat(201) } });
    fireEvent.change(screen.getByLabelText(/price \(usd\)/i), { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: /publish listing/i }));

    expect(await screen.findByText(/at most 200 characters/)).toBeTruthy();
    expect(postSpy).not.toHaveBeenCalled();
  });

  it.each([
    ['0', /greater than 0/i],
    ['-5', /greater than 0/i],
    ['abc', /enter a price/i],
  ] as const)('blocks invalid price %s client-side', async (bad, expected) => {
    useAuthStore.getState().login(fakeUser, 'tok');
    renderCreate();
    await screen.findByText('Electronics');

    fireEvent.change(screen.getByLabelText(/title/i), { target: { value: 'Used bike' } });
    fireEvent.change(screen.getByLabelText(/price \(usd\)/i), { target: { value: bad } });
    fireEvent.click(screen.getByRole('button', { name: /publish listing/i }));

    expect(await screen.findByText(expected)).toBeTruthy();
    expect(postSpy).not.toHaveBeenCalled();
  });

  it('maps a server 400 field message onto the price field', async () => {
    useAuthStore.getState().login(fakeUser, 'tok');
    renderCreate();
    await screen.findByText('Electronics');

    postSpy.mockRejectedValueOnce(axiosFailure(400, 'priceCents: must be greater than 0'));
    fillValidForm();
    fireEvent.click(screen.getByRole('button', { name: /publish listing/i }));

    // The raw "priceCents: …" backend phrasing must not leak into the UI —
    // only the human part lands on the price field.
    expect(await screen.findByText('must be greater than 0')).toBeTruthy();
    expect(screen.queryByText(/priceCents/)).toBeNull();
    expect(currentPath).toBe('/items/new');
  });

  it('creates the listing then uploads the photo and navigates to the detail page', async () => {
    useAuthStore.getState().login(fakeUser, 'tok');
    renderCreate();
    await screen.findByText('Electronics');

    postSpy.mockImplementation((url: string) => {
      if (url === '/items') return Promise.resolve(createdItem());
      const withPhoto = {
        ...createdItemPayload(),
        photoUrl: '/uploads/abc.png',
      };
      return Promise.resolve(envelope(withPhoto));
    });

    fillValidForm();
    fireEvent.change(screen.getByLabelText(/category/i), { target: { value: '1' } });
    const file = new File(['bytes'], 'bike.png', { type: 'image/png' });
    fireEvent.change(screen.getByLabelText(/photo/i), { target: { files: [file] } });
    fireEvent.click(screen.getByRole('button', { name: /publish listing/i }));

    await waitFor(() => expect(currentPath).toBe('/items/42'));
    expect(screen.getByText('detail probe')).toBeTruthy();

    // create payload: trimmed title, dollars→cents, numeric category id
    const [createUrl, createBody] = postSpy.mock.calls[0] as [string, Record<string, unknown>];
    expect(createUrl).toBe('/items');
    expect(createBody).toMatchObject({ title: 'Used bike', priceCents: 1250, categoryId: 1 });

    // photo upload: multipart FormData carrying the file
    const [photoUrl, formData] = postSpy.mock.calls[1] as [string, FormData];
    expect(photoUrl).toBe('/items/42/photo');
    const uploaded = formData.get('file') as File;
    expect(uploaded.name).toBe('bike.png');
    expect(uploaded.type).toBe('image/png');
  });

  it('keeps the seller on the page with a link when the photo upload fails', async () => {
    useAuthStore.getState().login(fakeUser, 'tok');
    renderCreate();
    await screen.findByText('Electronics');

    postSpy.mockImplementation((url: string) => {
      if (url === '/items') return Promise.resolve(createdItem());
      return Promise.reject(axiosFailure(400, 'file is empty'));
    });

    fillValidForm();
    const file = new File(['bytes'], 'bike.png', { type: 'image/png' });
    fireEvent.change(screen.getByLabelText(/photo/i), { target: { files: [file] } });
    fireEvent.click(screen.getByRole('button', { name: /publish listing/i }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toMatch(/photo upload failed/);
    expect(alert.textContent).toMatch(/file is empty/);
    expect(screen.getByRole('link', { name: /view your listing/i })).toBeTruthy();
    expect(currentPath).toBe('/items/new');
  });
});
