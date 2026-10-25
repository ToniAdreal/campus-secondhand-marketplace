import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import ErrorBoundary from '../components/ErrorBoundary';
import {
  APP_VERSION,
  installFrontendErrorTelemetry,
  reportFrontendError,
  routeTemplate,
} from './telemetry';

function Boom(): never {
  throw new Error('kaboom');
}

function fetchBody(call: unknown[]): Record<string, unknown> {
  const init = call[1] as { body?: string };
  return JSON.parse(init.body ?? '{}') as Record<string, unknown>;
}

describe('telemetry route templates', () => {
  it('maps the home route', () => {
    expect(routeTemplate('/')).toBe('/');
  });

  it('prefers the static create-listing route over the item-detail template', () => {
    expect(routeTemplate('/items/new')).toBe('/items/new');
  });

  it('reduces a concrete item id to the detail template', () => {
    expect(routeTemplate('/items/123')).toBe('/items/:id');
  });

  it('reduces an order id to the order template', () => {
    expect(routeTemplate('/orders/abc-def')).toBe('/orders/:id');
  });

  it('strips query strings and fragments before matching', () => {
    expect(routeTemplate('/items/42?session=abc123secret')).toBe('/items/:id');
    expect(routeTemplate('/orders/7#section')).toBe('/orders/:id');
  });

  it('maps unknown paths to unknown, never echoing the path', () => {
    expect(routeTemplate('/somewhere/secret-page')).toBe('unknown');
  });
});

describe('telemetry beacons', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal('fetch', fetchMock);
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    cleanup();
  });

  it('sends name + route template + version with keepalive and no credentials', () => {
    reportFrontendError('TypeError', '/items/42');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, Record<string, unknown>];
    expect(url).toBe('/api/telemetry/errors');
    expect(init.method).toBe('POST');
    expect(init.keepalive).toBe(true);
    expect(init.credentials).toBe('omit');
    const body = fetchBody(fetchMock.mock.calls[0]);
    expect(body.name).toBe('TypeError');
    expect(body.route).toBe('/items/:id');
    expect(body.version).toBe(APP_VERSION);
    // The beacon carries exactly the three sanitized fields — no
    // message, no stack, no query string.
    expect(Object.keys(body).sort()).toEqual(['name', 'route', 'version']);
  });

  it('never throws when the beacon request fails', async () => {
    fetchMock.mockRejectedValue(new Error('network down'));
    expect(() => reportFrontendError('Error', '/login')).not.toThrow();
    // Let the swallowed rejection settle so it cannot fail the run.
    await Promise.resolve();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('sends one beacon per window error event', () => {
    const uninstall = installFrontendErrorTelemetry();
    try {
      window.dispatchEvent(
        new ErrorEvent('error', { error: new TypeError('boom') }),
      );
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(fetchBody(fetchMock.mock.calls[0]).name).toBe('TypeError');
    } finally {
      uninstall();
    }
  });

  it('sends one beacon per unhandled rejection, named by the reason', () => {
    const uninstall = installFrontendErrorTelemetry();
    try {
      const event = new Event('unhandledrejection') as Event & {
        reason?: unknown;
      };
      event.reason = new RangeError('too big');
      window.dispatchEvent(event);
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(fetchBody(fetchMock.mock.calls[0]).name).toBe('RangeError');
    } finally {
      uninstall();
    }
  });

  it('labels a non-Error rejection reason as UnhandledRejection', () => {
    const uninstall = installFrontendErrorTelemetry();
    try {
      const event = new Event('unhandledrejection') as Event & {
        reason?: unknown;
      };
      event.reason = 'plain string reason';
      window.dispatchEvent(event);
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(fetchBody(fetchMock.mock.calls[0]).name).toBe('UnhandledRejection');
    } finally {
      uninstall();
    }
  });

  it('ErrorBoundary beacons once on a crash and still renders the fallback', () => {
    // MemoryRouter does not move window.location, so drive the real
    // pathname to the crashing (undefined) route before rendering.
    window.history.pushState({}, '', '/crash');
    render(
      <MemoryRouter initialEntries={['/crash']}>
        <Routes>
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

    expect(screen.getByText('Something went wrong')).toBeTruthy();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const body = fetchBody(fetchMock.mock.calls[0]);
    expect(body.name).toBe('Error');
    // /crash is no defined route: the beacon must say unknown, never
    // echo the crashing path.
    expect(body.route).toBe('unknown');
  });
});
