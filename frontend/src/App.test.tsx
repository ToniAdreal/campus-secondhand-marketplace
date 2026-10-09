import { describe, expect, it } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { lazy, type ComponentType } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import App from './App';

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

    render(
      <MemoryRouter initialEntries={['/slow']}>
        <Routes>
          <Route path="/" element={<App />}>
            <Route path="slow" element={<SlowPage />} />
          </Route>
        </Routes>
      </MemoryRouter>,
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
