import { QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import ReactDOM from 'react-dom/client';
import { RouterProvider, createBrowserRouter } from 'react-router-dom';
import App from './App';
import './index.css';
import CreateListingPage from './pages/CreateListingPage';
import HomePage from './pages/HomePage';
import ItemDetailPage from './pages/ItemDetailPage';
import LoginPage from './pages/LoginPage';
import MyOrdersPage from './pages/MyOrdersPage';
import OrderConfirmationPage from './pages/OrderConfirmationPage';
import { queryClient } from './queryClient';
import { useAuthStore } from './store/useAuthStore';

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
      { path: 'login', element: <LoginPage /> },
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
