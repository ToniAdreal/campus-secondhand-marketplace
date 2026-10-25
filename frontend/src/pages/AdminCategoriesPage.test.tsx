import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import type { Category } from '../api/categories';
import { useAuthStore } from '../store/useAuthStore';
import AdminCategoriesPage from './AdminCategoriesPage';

const getSpy = vi.spyOn(api, 'get');
const postSpy = vi.spyOn(api, 'post');
const patchSpy = vi.spyOn(api, 'patch');
const deleteSpy = vi.spyOn(api, 'delete');

const adminCaller: AuthUser = {
  id: 1,
  username: 'adminboss',
  email: 'admin@example.com',
  roles: ['ADMIN', 'USER'],
};

const taxonomy: Category[] = [
  { id: 1, name: 'Textbooks', slug: 'textbooks' },
  { id: 2, name: 'Electronics', slug: 'electronics' },
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

function renderAt(initialEntry: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/admin/categories" element={<AdminCategoriesPage />} />
          <Route path="/" element={<div>home page stub</div>} />
          <Route path="/login" element={<div>login page stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminCategoriesPage (backlog #229)', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('renders category rows from GET /categories', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(envelope(taxonomy));

    renderAt('/admin/categories');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'Textbooks' })).toBeTruthy());

    expect(screen.getByRole('cell', { name: 'textbooks' })).toBeTruthy();
    expect(screen.getByRole('cell', { name: 'Electronics' })).toBeTruthy();
    expect(getSpy).toHaveBeenCalledWith('/categories');
  });

  it('non-admin sessions bounce home and fire no request', async () => {
    useAuthStore.getState().login(
      { id: 9, username: 'plainuser', email: 'plain@example.com', roles: ['USER'] },
      'tok',
    );

    renderAt('/admin/categories');
    await waitFor(() => expect(screen.getByText('home page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('anonymous sessions bounce home and fire no request', async () => {
    renderAt('/admin/categories');
    await waitFor(() => expect(screen.getByText('home page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('a 403 from the API renders the honest not-authorized state, never a fake taxonomy', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockRejectedValue(axiosFailure(403, 'Access Denied'));

    renderAt('/admin/categories');
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toContain('not authorized');
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('a 401 mid-session navigates to /login', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockRejectedValue(axiosFailure(401, 'unauthorized'));

    renderAt('/admin/categories');
    await waitFor(() => expect(screen.getByText('login page stub')).toBeTruthy());
  });

  it('create fires POST with the name only when the slug is blank, then refetches the list', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(envelope(taxonomy));
    postSpy.mockResolvedValue(envelope({ id: 3, name: 'Books', slug: 'books' }));

    renderAt('/admin/categories');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'Textbooks' })).toBeTruthy());
    const callsBefore = getSpy.mock.calls.length;

    fireEvent.change(screen.getByLabelText('Category name'), { target: { value: 'Books' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(postSpy).toHaveBeenCalledWith('/categories', { name: 'Books' }));
    // The shared ['categories'] invalidation refetches the mounted list.
    await waitFor(() => expect(getSpy.mock.calls.length).toBeGreaterThan(callsBefore));
  });

  it('rename fires PATCH with the edited name and no slug when the slug input is cleared', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(envelope(taxonomy));
    patchSpy.mockResolvedValue(envelope({ id: 1, name: 'Used Books', slug: 'textbooks' }));

    renderAt('/admin/categories');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'Textbooks' })).toBeTruthy());

    fireEvent.click(screen.getAllByRole('button', { name: 'Rename' })[0]);
    fireEvent.change(screen.getByLabelText('Rename Textbooks'), { target: { value: 'Used Books' } });
    fireEvent.change(screen.getByLabelText('Slug for Textbooks (optional)'), {
      target: { value: '' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(patchSpy).toHaveBeenCalledWith('/categories/1', { name: 'Used Books' }),
    );
  });

  it('delete asks for confirmation, then a 409 renders the recategorize-first copy', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(envelope(taxonomy));
    deleteSpy.mockRejectedValue(axiosFailure(409, 'category in use'));

    renderAt('/admin/categories');
    await waitFor(() => expect(screen.getByRole('cell', { name: 'Textbooks' })).toBeTruthy());

    fireEvent.click(screen.getAllByRole('button', { name: 'Delete' })[0]);
    fireEvent.click(screen.getByRole('button', { name: 'Confirm delete' }));

    await waitFor(() => expect(deleteSpy).toHaveBeenCalledWith('/categories/1'));
    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain('Recategorize those listings first'),
    );
  });
});
