import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useEffect, useState } from 'react';
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom';
import RouteFocusManager from './RouteFocusManager';

/** Heading that only appears after a tick — a lazy route chunk landing. */
function LateHeadingPage() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    const timer = window.setTimeout(() => setReady(true), 50);
    return () => window.clearTimeout(timer);
  }, []);
  return ready ? <h1>Late heading</h1> : <p>loading the page…</p>;
}

function renderRoutedApp(initialEntry = '/') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <main>
        <RouteFocusManager />
        <Routes>
          <Route
            path="/"
            element={
              <div>
                <h1>Home heading</h1>
                <Link to="/next">Go next</Link>
                <Link to="/late">Go late</Link>
                <Link to="/bare">Go bare</Link>
              </div>
            }
          />
          <Route path="/next" element={<h1>Next heading</h1>} />
          <Route path="/late" element={<LateHeadingPage />} />
          <Route path="/bare" element={<p>No heading on this page</p>} />
        </Routes>
      </main>
    </MemoryRouter>,
  );
}

describe('RouteFocusManager (backlog #167)', () => {
  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
  });

  it('does not move focus on the initial page load', () => {
    renderRoutedApp();

    const heading = screen.getByRole('heading', { name: 'Home heading' });
    expect(document.activeElement).not.toBe(heading);
  });

  it('moves focus to the new page heading on navigation, with tabindex -1', async () => {
    renderRoutedApp();

    fireEvent.click(screen.getByRole('link', { name: 'Go next' }));

    const heading = await screen.findByRole('heading', { name: 'Next heading' });
    await waitFor(() => expect(document.activeElement).toBe(heading));
    // programmatically focusable only — never in the tab order
    expect(heading.getAttribute('tabindex')).toBe('-1');
  });

  it('waits for a lazily rendered heading and focuses it once it lands', async () => {
    renderRoutedApp();

    fireEvent.click(screen.getByRole('link', { name: 'Go late' }));

    const heading = await screen.findByRole('heading', { name: 'Late heading' });
    await waitFor(() => expect(document.activeElement).toBe(heading));
  });

  it('leaves focus alone when the new page has no heading', async () => {
    renderRoutedApp();

    fireEvent.click(screen.getByRole('link', { name: 'Go bare' }));

    expect(await screen.findByText('No heading on this page')).toBeTruthy();
    // focus was on the (now unmounted) link; the manager must not have
    // moved it onto any heading — there is none to move it to.
    expect(document.activeElement?.tagName).not.toBe('H1');
  });
});
