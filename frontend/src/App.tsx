import { Link, Outlet, useNavigate } from 'react-router-dom';
import { useAuthStore } from './store/useAuthStore';

function AuthNav() {
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const navigate = useNavigate();

  if (!user) {
    return (
      <Link to="/login" className="text-sm text-blue-600 hover:underline">
        Login
      </Link>
    );
  }

  return (
    <div className="flex items-center gap-3">
      <Link to="/orders" className="text-sm text-blue-600 hover:underline">
        My orders
      </Link>
      <Link to="/items/new" className="text-sm text-blue-600 hover:underline">
        Sell an item
      </Link>
      <Link to="/settings" className="text-sm text-blue-600 hover:underline">
        Settings
      </Link>
      <span className="text-sm text-neutral-600">{user.username}</span>
      <button
        type="button"
        onClick={() => {
          // logout() always resets local state (even when the server call
          // fails); navigate home either way.
          logout().then(
            () => navigate('/'),
            () => navigate('/'),
          );
        }}
        className="text-sm text-blue-600 hover:underline"
      >
        Logout
      </button>
    </div>
  );
}

export default function App() {
  return (
    <div className="min-h-screen bg-neutral-100 text-neutral-900">
      <header className="bg-white shadow-sm">
        <nav className="mx-auto flex max-w-5xl items-center justify-between px-4 py-3">
          <Link to="/" className="text-lg font-bold">
            Campus Marketplace
          </Link>
          <AuthNav />
        </nav>
      </header>
      <main className="mx-auto max-w-5xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  );
}
