import { QueryClientProvider } from '@tanstack/react-query';
import React, { lazy } from 'react';
import ReactDOM from 'react-dom/client';
import { RouterProvider, createBrowserRouter } from 'react-router-dom';
import App from './App';
import './index.css';
import { queryClient } from './queryClient';
import { useAuthStore } from './store/useAuthStore';

// Route-level code splitting: every page is its own chunk, loaded on
// first navigation to it. App, the query client and the auth store stay
// eager — session restore (hydrate below) must not wait on a chunk.
// App renders the single Suspense fallback around its outlet while a
// page chunk resolves.
const CreateListingPage = lazy(() => import('./pages/CreateListingPage'));
const HomePage = lazy(() => import('./pages/HomePage'));
const ItemDetailPage = lazy(() => import('./pages/ItemDetailPage'));
const ForgotPasswordPage = lazy(() => import('./pages/ForgotPasswordPage'));
const LoginPage = lazy(() => import('./pages/LoginPage'));
const ResetPasswordPage = lazy(() => import('./pages/ResetPasswordPage'));
const MyOrdersPage = lazy(() => import('./pages/MyOrdersPage'));
const OrderConfirmationPage = lazy(() => import('./pages/OrderConfirmationPage'));
const SessionsPage = lazy(() => import('./pages/SessionsPage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));
const SellerOrdersPage = lazy(() => import('./pages/SellerOrdersPage'));
const AdminUsersPage = lazy(() => import('./pages/AdminUsersPage'));
const NotFoundPage = lazy(() => import('./pages/NotFoundPage'));

const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'items/new', element: <CreateListingPage /> },
      { path: 'items/:id', element: <ItemDetailPage /> },
      { path: 'orders', element: <MyOrdersPage /> },
      { path: 'orders/:id', element: <OrderConfirmationPage /> },
      { path: 'seller/orders', element: <SellerOrdersPage /> },
      { path: 'login', element: <LoginPage /> },
      { path: 'forgot-password', element: <ForgotPasswordPage /> },
      { path: 'reset-password', element: <ResetPasswordPage /> },
      { path: 'settings', element: <SettingsPage /> },
      { path: 'settings/sessions', element: <SessionsPage /> },
      { path: 'admin/users', element: <AdminUsersPage /> },
      // catch-all — unknown paths render the 404 inside the app shell
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);

// Restore the session on boot: a surviving httpOnly refresh cookie is
// enough to come back logged in (silent refresh -> /me). Fire-and-forget —
// hydrate() never throws; the header flips from "Login" to the username
// when it lands.
useAuthStore.getState().hydrate();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </React.StrictMode>,
);
