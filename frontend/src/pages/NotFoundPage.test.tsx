import { describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import App from '../App';
import NotFoundPage from './NotFoundPage';

let currentPath = '';
function LocationProbe() {
  currentPath = useLocation().pathname;
  return null;
}

function renderUnknownPath() {
  // App's shell now runs the unread-count query (#106), so — like
  // main.tsx — the test tree provides a QueryClient.
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/nope-here']}>
        <LocationProbe />
        <Routes>
          <Route path="/" element={<App />}>
            <Route path="/" element={<div>home probe</div>} />
            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('NotFoundPage', () => {
  it('renders inside the app shell for an unknown path', () => {
    renderUnknownPath();

    // the page itself
    expect(screen.getByRole('heading', { name: /this page doesn't exist/i })).toBeTruthy();
    // the app shell around it — header brand link from App
    expect(screen.getByRole('link', { name: 'Campus Marketplace' })).toBeTruthy();

    cleanup();
  });

  it('the back-to-home link points at / and navigates there', () => {
    renderUnknownPath();

    const backLink = screen.getByRole('link', { name: /back to home/i }) as HTMLAnchorElement;
    expect(backLink.getAttribute('href')).toBe('/');

    fireEvent.click(backLink);
    expect(currentPath).toBe('/');
    expect(screen.getByText('home probe')).toBeTruthy();

    cleanup();
  });
});
