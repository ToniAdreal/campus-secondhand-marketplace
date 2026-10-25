/**
 * Frontend error telemetry beacons (backlog #223).
 *
 * The route-level ErrorBoundary used to log a caught render error with
 * `console.error` only, so a crash in a real browser was invisible to
 * the operator. This module is the sending half of the
 * POST /api/telemetry/errors intake: one best-effort beacon per crash,
 * sent with `fetch(keepalive)` so it survives a page that is already
 * tearing down, and never allowed to throw into the fallback UI or
 * into a window error handler (a failed beacon is silently dropped —
 * telemetry must never become a second crash).
 *
 * Privacy is structural, mirroring the backend intake: the beacon
 * carries exactly three fields — the error name, the route *template*
 * and the app version. It never carries the error message, any stack
 * text, any URL query string, or any storage/cookie data, and it is
 * sent with `credentials: 'omit'` so no cookie rides along either.
 * The route is reduced to a template here (a concrete id segment
 * becomes `:id`, an unknown path becomes `unknown`) and the backend
 * sanitizes it a second time before logging or counting.
 */

// Mirrors the `version` field in frontend/package.json. It is a plain
// constant (not an import of package.json) so the beacon module stays
// importable from any test without a bundler alias.
export const APP_VERSION = '0.1.0';

const TELEMETRY_URL = '/api/telemetry/errors';

/**
 * Route templates the SPA actually defines (see main.tsx). A template
 * segment of `:id` matches any single non-empty path segment. Templates
 * are tried most-static-first, so `/items/new` wins over `/items/:id`
 * for the create-listing page.
 */
const ROUTE_TEMPLATES = [
  '/',
  '/items/new',
  '/items/:id',
  '/orders',
  '/orders/:id',
  '/cart',
  '/offers',
  '/favorites',
  '/notifications',
  '/my-listings',
  '/seller/orders',
  '/seller/payouts',
  '/seller/:id',
  '/login',
  '/register',
  '/verify-email',
  '/forgot-password',
  '/reset-password',
  '/settings',
  '/settings/sessions',
  '/admin/users',
  '/admin/audit-log',
  '/admin/reports',
  '/admin/disputes',
  '/admin/appeals',
  '/admin/payments',
  '/admin/dashboard',
];

function templateSegments(template: string): string[] {
  return template.split('/').filter((segment) => segment.length > 0);
}

function matchesTemplate(pathSegments: string[], template: string): boolean {
  const templateParts = templateSegments(template);
  if (templateParts.length !== pathSegments.length) {
    return false;
  }
  return templateParts.every(
    (part, index) => part === ':id' || part === pathSegments[index],
  );
}

/**
 * Reduces a concrete pathname to its route template, or `'unknown'`
 * when it matches no defined route. The query string and fragment are
 * stripped before matching (a caller passing `location.pathname`
 * already has neither, but a defensive strip costs nothing and keeps a
 * query token from ever reaching the beacon).
 */
export function routeTemplate(pathname: string): string {
  if (!pathname) {
    return 'unknown';
  }
  let path = pathname.split('?')[0].split('#')[0];
  if (!path.startsWith('/')) {
    path = `/${path}`;
  }
  if (path.length > 1 && path.endsWith('/')) {
    path = path.slice(0, -1);
  }
  if (path === '/') {
    return '/';
  }
  const segments = path.split('/').filter((segment) => segment.length > 0);
  const sorted = [...ROUTE_TEMPLATES].sort((a, b) => {
    const staticA = templateSegments(a).filter((part) => part !== ':id').length;
    const staticB = templateSegments(b).filter((part) => part !== ':id').length;
    return staticB - staticA;
  });
  for (const template of sorted) {
    if (matchesTemplate(segments, template)) {
      return template;
    }
  }
  return 'unknown';
}

/**
 * Sends one beacon for a crash. `pathname` is the page the crash
 * happened on (usually `window.location.pathname`); it is reduced to a
 * template before sending. Never throws: a missing `fetch`, a rejected
 * request, or a serialization failure all drop the beacon silently.
 */
export function reportFrontendError(
  errorName: string,
  pathname?: string,
): void {
  try {
    if (typeof fetch !== 'function') {
      return;
    }
    const name =
      typeof errorName === 'string' && errorName.length > 0 ? errorName : 'Error';
    const route =
      typeof pathname === 'string' ? routeTemplate(pathname) : 'unknown';
    const body = JSON.stringify({ name, route, version: APP_VERSION });
    // keepalive: the beacon must survive a page that is unloading;
    // credentials omit: no cookie or storage value may ride along.
    void fetch(TELEMETRY_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
      keepalive: true,
      credentials: 'omit',
    }).catch(() => {
      // Telemetry is best-effort by design: a failed beacon is dropped,
      // never surfaced to the user or to an error handler.
    });
  } catch {
    // A beacon must never become a second crash.
  }
}

function errorNameOf(value: unknown): string {
  if (value instanceof Error && value.name) {
    return value.name;
  }
  // Any non-Error reason (a string, a value, undefined) has no error
  // name of its own: it is labelled as an unhandled rejection, never
  // mistaken for a real Error subclass.
  return 'UnhandledRejection';
}

/**
 * Installs the `window` crash hooks: `error` events (uncaught
 * exceptions, including resource errors that carry no Error object)
 * and `unhandledrejection` events (rejected promises with no handler).
 * Each event sends exactly one beacon for that crash. Returns an
 * uninstall function (used by tests; the app installs once in main.tsx
 * and never uninstalls).
 *
 * Honest scope, matching the ErrorBoundary's: this catches uncaught
 * runtime errors only. Errors already handled by application code, and
 * errors inside the beacon path itself, never reach these hooks.
 */
export function installFrontendErrorTelemetry(): () => void {
  if (typeof window === 'undefined' || typeof window.addEventListener !== 'function') {
    return () => {};
  }
  const onError = (event: ErrorEvent): void => {
    try {
      const pathname =
        typeof window !== 'undefined' ? window.location.pathname : undefined;
      reportFrontendError(errorNameOf(event.error), pathname);
    } catch {
      // The hook itself must never throw.
    }
  };
  const onUnhandledRejection = (event: PromiseRejectionEvent): void => {
    try {
      const pathname =
        typeof window !== 'undefined' ? window.location.pathname : undefined;
      reportFrontendError(errorNameOf((event as unknown as { reason?: unknown }).reason), pathname);
    } catch {
      // The hook itself must never throw.
    }
  };
  window.addEventListener('error', onError);
  window.addEventListener('unhandledrejection', onUnhandledRejection);
  return () => {
    window.removeEventListener('error', onError);
    window.removeEventListener('unhandledrejection', onUnhandledRejection);
  };
}
