import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import ErrorBoundary from './ErrorBoundary';

function Boom(): never {
  throw new Error('kaboom');
}

let currentPath = '';
function LocationProbe() {
  currentPath = useLocation().pathname;
  return null;
}

function renderCrashingRoute() {
  return render(
    <MemoryRouter initialEntries={['/crash']}>
      <LocationProbe />
      <Routes>
        <Route
          path="/"
          element={
            <ErrorBoundary>
              <div>home probe</div>
            </ErrorBoundary>
          }
        />
        <Route
          path="/crash"
          element={
            <ErrorBoundary>
              <Boom />
            </ErrorBoundary>
          }
        />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ErrorBoundary', () => {
  beforeEach(() => {
    // React and the boundary both log the caught error; silence it so the
    // test output stays readable, and assert on the spy where it matters.
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
    cleanup();
  });

  it('renders children unchanged when nothing throws', () => {
    render(
      <MemoryRouter>
        <ErrorBoundary>
          <div>healthy child</div>
        </ErrorBoundary>
      </MemoryRouter>,
    );

    expect(screen.getByText('healthy child')).toBeTruthy();
    expect(screen.queryByRole('heading', { name: /something went wrong/i })).toBeNull();
  });

  it('a throwing component renders the friendly fallback and logs the error', () => {
    renderCrashingRoute();

    expect(screen.getByRole('heading', { name: /something went wrong/i })).toBeTruthy();
    expect(screen.getByRole('link', { name: /back to home/i })).toBeTruthy();
    // the boundary logged the caught error via console.error
    expect(console.error).toHaveBeenCalled();
    const logged = (console.error as unknown as { mock: { calls: unknown[][] } }).mock.calls
      .flat()
      .map((arg) => (arg instanceof Error ? arg.message : String(arg)))
      .join(' ');
    expect(logged).toContain('kaboom');
  });

  it('the back-to-home link navigates to / and the home content renders', () => {
    renderCrashingRoute();

    const backLink = screen.getByRole('link', { name: /back to home/i }) as HTMLAnchorElement;
    expect(backLink.getAttribute('href')).toBe('/');

    fireEvent.click(backLink);

    expect(currentPath).toBe('/');
    expect(screen.getByText('home probe')).toBeTruthy();
  });

  it('clicking back-to-home resets the same boundary so a healthy render recovers', () => {
    // One boundary instance around content that crashes once, then is
    // healthy — the reset on the fallback link is what lets it recover
    // without a remount (the real App shell never remounts the boundary).
    let shouldThrow = true;
    function Flaky() {
      if (shouldThrow) throw new Error('first render only');
      return <div>recovered child</div>;
    }

    render(
      <MemoryRouter initialEntries={['/crash']}>
        <ErrorBoundary>
          <Flaky />
        </ErrorBoundary>
      </MemoryRouter>,
    );
    expect(screen.getByRole('heading', { name: /something went wrong/i })).toBeTruthy();

    shouldThrow = false;
    fireEvent.click(screen.getByRole('link', { name: /back to home/i }));

    expect(screen.getByText('recovered child')).toBeTruthy();
  });
});
