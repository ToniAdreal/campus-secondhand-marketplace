import { useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
import {
  describeAdminUserError,
  isForbidden,
  useAdminUsers,
  useDisableAdminUser,
  useEnableAdminUser,
  type AdminUser,
} from '../api/admin';
import { useDebouncedSearchParam } from '../hooks/useDebouncedSearchParam';
import { useAuthStore } from '../store/useAuthStore';

/**
 * Admin users — the ADMIN user-management page at /admin/users
 * (backlog #111), the frontend for GET /api/admin/users (#105) and the
 * disable/enable endpoints (#88).
 *
 * Authorization is honest about its layers: the nav entry and this route
 * are hidden from non-ADMIN sessions (client-side only — a non-admin
 * session bounces home and fires no request), but the backend's
 * @PreAuthorize is the real gate. If the API still answers 403 (the role
 * was revoked after login, a stale store, …) the page renders an honest
 * not-authorized state — never a fabricated list.
 *
 * The table is paginated (Previous/Next over the server page) and the
 * search box is synced to `?q=` through the same debounced URL param the
 * browse page uses, so a filtered view is refresh-safe and shareable.
 * Disable asks for confirmation first (it kills every live session of
 * the account); rows for the caller themselves and for other ADMINs
 * render no disable button, mirroring the backend's 403 guards. Enable
 * is restorative and carries no such guard.
 */
export default function AdminUsersPage() {
  const user = useAuthStore((s) => s.user);
  const isAdmin = user !== null && user.roles.includes('ADMIN');
  const [searchInput, searchQuery, setSearchInput] = useDebouncedSearchParam('q', 300);
  const [page, setPage] = useState(0);
  const [actionError, setActionError] = useState<string | null>(null);

  // A new search term starts again from the first page — page 3 of the
  // unfiltered list is meaningless under a fresh filter.
  useEffect(() => {
    setPage(0);
  }, [searchQuery]);

  const users = useAdminUsers(page, searchQuery, isAdmin);
  const disableUser = useDisableAdminUser();
  const enableUser = useEnableAdminUser();

  if (!isAdmin) {
    return <Navigate to="/" replace />;
  }

  if (users.isError && isForbidden(users.error)) {
    return (
      <div>
        <h1 className="mb-4 text-2xl font-bold">Admin users</h1>
        <p role="alert" className="rounded-lg bg-white p-6 text-center text-neutral-600 shadow-sm">
          You are not authorized to view this page. User management is limited to ADMIN
          accounts, and the server refused this request.
        </p>
      </div>
    );
  }

  const rows: AdminUser[] = users.data?.content ?? [];
  const totalPages = users.data?.totalPages ?? 0;
  const busy = disableUser.isLoading || enableUser.isLoading;

  const handleDisable = (target: AdminUser) => {
    setActionError(null);
    // Disabling signs the account out everywhere at once — confirm first,
    // mirroring the refund / current-session-revoke flows.
    if (
      !window.confirm(
        `Disable ${target.username}? They will be signed out everywhere and cannot log in until re-enabled.`,
      )
    ) {
      return;
    }
    disableUser.mutate(target.id, {
      onError: (error) => setActionError(describeAdminUserError(error)),
    });
  };

  const handleEnable = (target: AdminUser) => {
    setActionError(null);
    enableUser.mutate(target.id, {
      onError: (error) => setActionError(describeAdminUserError(error)),
    });
  };

  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold">Admin users</h1>
      <div className="mb-4">
        <label htmlFor="admin-user-search" className="sr-only">
          Search users
        </label>
        <input
          id="admin-user-search"
          type="search"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          placeholder="Search by username or email…"
          className="w-full rounded-lg border border-neutral-300 bg-white px-4 py-2 shadow-sm
                     focus:border-sky-500 focus:outline-none"
        />
      </div>
      {actionError && (
        <p role="alert" className="mb-4 text-sm text-red-600">
          {actionError}
        </p>
      )}
      {users.isLoading && <p>Loading users…</p>}
      {users.isError && (
        <p role="alert" className="text-red-600">
          {describeAdminUserError(users.error)}
        </p>
      )}
      {users.isSuccess && rows.length === 0 && (
        <div className="rounded-lg bg-white p-6 text-center shadow-sm">
          <p className="text-neutral-600">
            {searchQuery !== '' ? `No users match "${searchQuery}".` : 'No users yet.'}
          </p>
        </div>
      )}
      {rows.length > 0 && (
        <>
          <div className="overflow-x-auto rounded-lg bg-white shadow-sm">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-neutral-200 text-neutral-500">
                  <th scope="col" className="px-4 py-3 font-medium">Username</th>
                  <th scope="col" className="px-4 py-3 font-medium">Email</th>
                  <th scope="col" className="px-4 py-3 font-medium">Roles</th>
                  <th scope="col" className="px-4 py-3 font-medium">Status</th>
                  <th scope="col" className="px-4 py-3 font-medium">Created</th>
                  <th scope="col" className="px-4 py-3 font-medium">Actions</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => {
                  // Mirror the backend's disable guards (#88): no disable
                  // button for the caller's own row or another ADMIN's.
                  const canDisable =
                    !row.disabled && row.id !== user.id && !row.roles.includes('ADMIN');
                  return (
                    <tr key={row.id} className="border-b border-neutral-100 last:border-0">
                      <td className="px-4 py-3 font-medium">{row.username}</td>
                      <td className="px-4 py-3">{row.email}</td>
                      <td className="px-4 py-3">{row.roles.join(', ')}</td>
                      <td className="px-4 py-3">{row.disabled ? 'Disabled' : 'Active'}</td>
                      <td className="px-4 py-3">
                        {new Date(row.createdAt).toLocaleDateString()}
                      </td>
                      <td className="px-4 py-3">
                        {row.disabled ? (
                          <button
                            type="button"
                            onClick={() => handleEnable(row)}
                            disabled={busy}
                            className="rounded bg-green-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-50"
                          >
                            Enable
                          </button>
                        ) : canDisable ? (
                          <button
                            type="button"
                            onClick={() => handleDisable(row)}
                            disabled={busy}
                            className="rounded border border-red-600 px-3 py-1.5 text-sm font-medium text-red-600 hover:bg-red-50 disabled:opacity-50"
                          >
                            Disable
                          </button>
                        ) : (
                          <span className="text-neutral-400">—</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <div className="mt-4 flex items-center gap-3">
            <button
              type="button"
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={page <= 0 || users.isFetching}
              className="rounded bg-neutral-800 px-4 py-1.5 text-sm font-medium text-white hover:bg-neutral-700 disabled:opacity-50"
            >
              Previous
            </button>
            <span className="text-sm text-neutral-600">
              Page {totalPages === 0 ? 0 : page + 1} of {totalPages}
            </span>
            <button
              type="button"
              onClick={() => setPage((p) => p + 1)}
              disabled={page + 1 >= totalPages || users.isFetching}
              className="rounded bg-neutral-800 px-4 py-1.5 text-sm font-medium text-white hover:bg-neutral-700 disabled:opacity-50"
            >
              Next
            </button>
          </div>
        </>
      )}
    </div>
  );
}
