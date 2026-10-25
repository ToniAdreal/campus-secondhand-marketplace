import { useState } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import {
  describeAdminCategoryError,
  isUnauthorized,
  useAdminCategories,
  useCreateCategory,
  useDeleteCategory,
  useRenameCategory,
  type Category,
} from '../api/categories';
import { isForbidden } from '../api/admin';
import { useAuthStore } from '../store/useAuthStore';

/**
 * Admin category management — /admin/categories (backlog #229), the
 * frontend for the #70 ADMIN endpoints (POST /api/categories, PATCH
 * /api/categories/{id}, DELETE /api/categories/{id}) that previously had
 * no SPA surface at all — an admin could only manage the taxonomy with
 * curl.
 *
 * Authorization mirrors /admin/reports (#160): the nav entry and route
 * are hidden from non-ADMIN sessions (a non-admin session bounces home
 * and fires no request — the query is gated on isAdmin), but the
 * backend's @PreAuthorize is the real gate, so a 403 from the API renders
 * an honest not-authorized state, never a fake/empty taxonomy. A 401
 * mid-session (the session expired after the page loaded) navigates to
 * /login, like the other session-owned pages.
 *
 * Writes go through the #87 cache-evict contract on both sides: the
 * backend evicts its categories cache on every successful write, and
 * each mutation invalidates the SPA's shared ['categories'] key, so the
 * admin list and the create-listing/browse dropdowns all refetch the
 * fresh taxonomy without a reload. A blank slug is omitted from the
 * payload on purpose: create derives it from the name, rename keeps the
 * current slug — sending an empty string would silently rewrite a slug
 * sellers already know.
 *
 * Delete is a two-step inline confirm (no window.confirm, so the flow
 * stays testable and keyboard-visible), and its 409 maps to the
 * recategorize-first copy: the fix is editing the listings onto another
 * category, not retrying the delete.
 */
export default function AdminCategoriesPage() {
  const user = useAuthStore((s) => s.user);
  const isAdmin = user !== null && user.roles.includes('ADMIN');
  const location = useLocation();

  const [createName, setCreateName] = useState('');
  const [createSlug, setCreateSlug] = useState('');
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editName, setEditName] = useState('');
  const [editSlug, setEditSlug] = useState('');
  const [confirmDeleteId, setConfirmDeleteId] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const categories = useAdminCategories(isAdmin);
  const createCategory = useCreateCategory();
  const renameCategory = useRenameCategory();
  const deleteCategory = useDeleteCategory();

  if (!isAdmin) {
    return <Navigate to="/" replace />;
  }

  if (categories.isError && isUnauthorized(categories.error)) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  if (categories.isError && isForbidden(categories.error)) {
    return (
      <div>
        <h1 className="mb-4 text-2xl font-bold">Admin categories</h1>
        <p role="alert" className="rounded-lg bg-white p-6 text-center text-neutral-600 shadow-sm">
          You are not authorized to view this page. Category management is limited to ADMIN
          accounts, and the server refused this request.
        </p>
      </div>
    );
  }

  const rows: Category[] = categories.data ?? [];

  const handleCreate = () => {
    setActionError(null);
    createCategory.mutate(
      { name: createName.trim(), slug: createSlug.trim() },
      {
        onSuccess: () => {
          setCreateName('');
          setCreateSlug('');
        },
        onError: (error) => setActionError(describeAdminCategoryError(error, 'save')),
      },
    );
  };

  const startEdit = (row: Category) => {
    setActionError(null);
    setEditingId(row.id);
    setEditName(row.name);
    setEditSlug(row.slug);
  };

  const handleRename = (id: number) => {
    setActionError(null);
    renameCategory.mutate(
      { id, name: editName.trim(), slug: editSlug.trim() },
      {
        onSuccess: () => setEditingId(null),
        onError: (error) => setActionError(describeAdminCategoryError(error, 'save')),
      },
    );
  };

  const handleDelete = (id: number) => {
    setActionError(null);
    deleteCategory.mutate(id, {
      onSuccess: () => setConfirmDeleteId(null),
      onError: (error) => setActionError(describeAdminCategoryError(error, 'delete')),
    });
  };

  const busy = createCategory.isLoading || renameCategory.isLoading || deleteCategory.isLoading;

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">Admin categories</h1>
      <p className="mb-4 text-sm text-neutral-600">
        The listing taxonomy sellers pick from. Renaming a category never changes its slug
        unless you type a new one — links and listings keep the slug they already know.
      </p>

      {categories.isLoading && <p>Loading categories…</p>}
      {categories.isError && (
        <p role="alert" className="text-red-600">
          {describeAdminCategoryError(categories.error, 'save')}
        </p>
      )}
      {actionError && (
        <p role="alert" className="mb-3 text-red-600">
          {actionError}
        </p>
      )}

      <section aria-label="Create category" className="mb-6 rounded-lg bg-white p-4 shadow-sm">
        <h2 className="mb-3 text-lg font-semibold">Create category</h2>
        <div className="flex flex-col gap-2 sm:flex-row">
          <label className="flex-1">
            <span className="sr-only">Category name</span>
            <input
              type="text"
              aria-label="Category name"
              placeholder="Name"
              maxLength={60}
              value={createName}
              onChange={(e) => setCreateName(e.target.value)}
              className="w-full rounded-lg border border-neutral-300 px-4 py-2 shadow-sm focus:border-sky-500 focus:outline-none"
            />
          </label>
          <label className="flex-1">
            <span className="sr-only">Category slug</span>
            <input
              type="text"
              aria-label="Category slug (optional)"
              placeholder="Slug (optional — derived from the name)"
              maxLength={60}
              value={createSlug}
              onChange={(e) => setCreateSlug(e.target.value)}
              className="w-full rounded-lg border border-neutral-300 px-4 py-2 shadow-sm focus:border-sky-500 focus:outline-none"
            />
          </label>
          <button
            type="button"
            onClick={handleCreate}
            disabled={busy || createName.trim() === ''}
            className="rounded bg-sky-600 px-4 py-2 text-sm font-medium text-white hover:bg-sky-700 disabled:opacity-50"
          >
            Create
          </button>
        </div>
      </section>

      {categories.isSuccess && rows.length === 0 && (
        <div className="rounded-lg bg-white p-6 text-center shadow-sm">
          <p className="text-neutral-600">No categories yet.</p>
        </div>
      )}

      {rows.length > 0 && (
        <div className="overflow-x-auto rounded-lg bg-white shadow-sm">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-neutral-200 text-neutral-500">
                <th scope="col" className="px-4 py-3 font-medium">Name</th>
                <th scope="col" className="px-4 py-3 font-medium">Slug</th>
                <th scope="col" className="px-4 py-3 font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id} className="border-b border-neutral-100 last:border-0">
                  {editingId === row.id ? (
                    <>
                      <td className="px-4 py-3">
                        <label>
                          <span className="sr-only">Category name</span>
                          <input
                            type="text"
                            aria-label={`Rename ${row.name}`}
                            maxLength={60}
                            value={editName}
                            onChange={(e) => setEditName(e.target.value)}
                            className="w-full rounded-lg border border-neutral-300 px-3 py-1 focus:border-sky-500 focus:outline-none"
                          />
                        </label>
                      </td>
                      <td className="px-4 py-3">
                        <label>
                          <span className="sr-only">Category slug</span>
                          <input
                            type="text"
                            aria-label={`Slug for ${row.name} (optional)`}
                            maxLength={60}
                            value={editSlug}
                            onChange={(e) => setEditSlug(e.target.value)}
                            className="w-full rounded-lg border border-neutral-300 px-3 py-1 focus:border-sky-500 focus:outline-none"
                          />
                        </label>
                      </td>
                      <td className="px-4 py-3">
                        <span className="flex gap-2">
                          <button
                            type="button"
                            onClick={() => handleRename(row.id)}
                            disabled={busy || editName.trim() === ''}
                            className="rounded bg-sky-600 px-3 py-1 text-xs font-medium text-white hover:bg-sky-700 disabled:opacity-50"
                          >
                            Save
                          </button>
                          <button
                            type="button"
                            onClick={() => setEditingId(null)}
                            disabled={busy}
                            className="rounded bg-neutral-200 px-3 py-1 text-xs font-medium text-neutral-800 hover:bg-neutral-300 disabled:opacity-50"
                          >
                            Cancel
                          </button>
                        </span>
                      </td>
                    </>
                  ) : (
                    <>
                      <td className="px-4 py-3 font-medium">{row.name}</td>
                      <td className="px-4 py-3">{row.slug}</td>
                      <td className="px-4 py-3">
                        {confirmDeleteId === row.id ? (
                          <span className="flex items-center gap-2">
                            <span className="text-xs text-neutral-600">
                              Delete this category? Listings using it block the delete.
                            </span>
                            <button
                              type="button"
                              onClick={() => handleDelete(row.id)}
                              disabled={busy}
                              className="rounded bg-red-600 px-3 py-1 text-xs font-medium text-white hover:bg-red-700 disabled:opacity-50"
                            >
                              Confirm delete
                            </button>
                            <button
                              type="button"
                              onClick={() => setConfirmDeleteId(null)}
                              disabled={busy}
                              className="rounded bg-neutral-200 px-3 py-1 text-xs font-medium text-neutral-800 hover:bg-neutral-300 disabled:opacity-50"
                            >
                              Keep
                            </button>
                          </span>
                        ) : (
                          <span className="flex gap-2">
                            <button
                              type="button"
                              onClick={() => startEdit(row)}
                              disabled={busy}
                              className="rounded bg-neutral-200 px-3 py-1 text-xs font-medium text-neutral-800 hover:bg-neutral-300 disabled:opacity-50"
                            >
                              Rename
                            </button>
                            <button
                              type="button"
                              onClick={() => setConfirmDeleteId(row.id)}
                              disabled={busy}
                              className="rounded bg-red-600 px-3 py-1 text-xs font-medium text-white hover:bg-red-700 disabled:opacity-50"
                            >
                              Delete
                            </button>
                          </span>
                        )}
                      </td>
                    </>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
