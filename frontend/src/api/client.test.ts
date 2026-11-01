import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  ApiError,
  REQUEST_TIMEOUT_MESSAGE,
  REQUEST_TIMEOUT_MS,
  api,
  describeRequestError,
  getAccessToken,
  isApiError,
  isRequestTimeout,
  setAccessToken,
} from './client';

// Minimal fake fetch: lets us script HTTP behavior without a backend.
// The handler sees the same (url, init) shape the real fetch gets.
type FetchHandler = (url: string, init: any) => Promise<any>;

function installFetch(handler: FetchHandler) {
  vi.stubGlobal('fetch', handler);
}

function jsonResponse(data: any, status = 200) {
  const body = data === undefined ? '' : JSON.stringify(data);
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 200 ? 'OK' : 'Error',
    headers: new Headers({ 'Content-Type': 'application/json' }),
    text: async () => body,
    json: async () => data,
    blob: async () => new Blob([body], { type: 'application/json' }),
  };
}

function envelopeResponse(data: any, status = 200) {
  return jsonResponse({ code: 0, message: 'ok', data }, status);
}

/** An HTTP failure whose parsed body carries an envelope message. */
function errorResponse(status: number, message: string) {
  return jsonResponse({ code: status, message, data: null }, status);
}

function readHeader(init: any, name: string): string | undefined {
  const h = init?.headers;
  if (!h) return undefined;
  return typeof h.get === 'function' ? h.get(name) ?? undefined : h[name];
}

const UUID_SHAPE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

describe('api client', () => {
  beforeEach(() => {
    setAccessToken(null);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('attaches the in-memory access token as a Bearer header', async () => {
    setAccessToken('tok-123');
    let seen: string | undefined;
    installFetch(async (_url, init) => {
      seen = readHeader(init, 'Authorization');
      return envelopeResponse({});
    });

    await api.get('/items');
    expect(seen).toBe('Bearer tok-123');
  });

  it('prefixes the /api base and sends credentials (httpOnly refresh cookie)', async () => {
    let seenUrl = '';
    let seenCredentials: unknown;
    installFetch(async (url, init) => {
      seenUrl = url;
      seenCredentials = init?.credentials;
      return envelopeResponse({});
    });

    await api.get('/items');
    expect(seenUrl).toBe('/api/items');
    expect(seenCredentials).toBe('include');
  });

  it('issues exactly one refresh for concurrent 401s and retries every request', async () => {
    setAccessToken('expired-token');
    let refreshCalls = 0;
    let itemCalls = 0;

    installFetch(async (url, init) => {
      if (url.includes('/auth/refresh')) {
        refreshCalls++;
        // hold the refresh in flight so the three 401s overlap
        await new Promise((r) => setTimeout(r, 20));
        return envelopeResponse({ accessToken: 'fresh-456' });
      }
      itemCalls++;
      if (readHeader(init, 'Authorization') !== 'Bearer fresh-456') {
        return errorResponse(401, 'unauthorized');
      }
      return envelopeResponse({ ok: true });
    });

    const results = await Promise.all([
      api.get('/items'),
      api.get('/items'),
      api.get('/items'),
    ]);

    expect(refreshCalls).toBe(1); // single-flight, not one per failed request
    expect(itemCalls).toBe(6); // 3 initial + 3 retried with the fresh token
    expect(results.every((r) => (r.data as any).data.ok)).toBe(true);
    expect(getAccessToken()).toBe('fresh-456');
  });

  it('clears the token and rejects when refresh fails', async () => {
    setAccessToken('expired-token');
    installFetch(async (url) => {
      if (url.includes('/auth/refresh')) {
        return errorResponse(401, 'refresh expired');
      }
      return errorResponse(401, 'unauthorized');
    });

    await expect(api.get('/items')).rejects.toThrow('unauthorized');
    expect(getAccessToken()).toBeNull();
  });

  it('attempts a fresh refresh for a later 401 wave after a failed refresh', async () => {
    setAccessToken('expired-token');
    let refreshCalls = 0;
    installFetch(async (url, init) => {
      if (url.includes('/auth/refresh')) {
        refreshCalls++;
        if (refreshCalls === 1) {
          return errorResponse(500, 'refresh backend down');
        }
        return envelopeResponse({ accessToken: 'recovered-789' });
      }
      if (readHeader(init, 'Authorization') !== 'Bearer recovered-789') {
        return errorResponse(401, 'unauthorized');
      }
      return envelopeResponse({ ok: true });
    });

    // Wave 1: refresh fails → request rejects, token cleared.
    await expect(api.get('/items')).rejects.toThrow('unauthorized');
    expect(refreshCalls).toBe(1);
    expect(getAccessToken()).toBeNull();

    // Wave 2 (a later 401): a NEW refresh is attempted — the settled promise
    // from wave 1 must not be reused (refreshPromise is reset in finally()).
    setAccessToken('expired-token');
    const res = await api.get('/items');
    expect(refreshCalls).toBe(2);
    expect((res.data as any).data.ok).toBe(true);
    expect(getAccessToken()).toBe('recovered-789');
  });

  it('does not touch the refresh queue for non-401 errors', async () => {
    setAccessToken('tok-123');
    let refreshCalls = 0;
    installFetch(async (url) => {
      if (url.includes('/auth/refresh')) {
        refreshCalls++;
        return envelopeResponse({ accessToken: 'x' });
      }
      return errorResponse(403, 'forbidden');
    });

    await expect(api.get('/items')).rejects.toThrow('forbidden');
    expect(refreshCalls).toBe(0);
    expect(getAccessToken()).toBe('tok-123'); // token left alone
  });

  it('stamps a UUID-shaped X-Request-ID on a plain request (backlog #126)', async () => {
    let seen: string | undefined;
    installFetch(async (_url, init) => {
      seen = readHeader(init, 'X-Request-ID');
      return envelopeResponse({});
    });

    await api.get('/items');
    expect(seen).toMatch(UUID_SHAPE);
  });

  it('gives two separate calls distinct X-Request-IDs (backlog #126)', async () => {
    const seen: (string | undefined)[] = [];
    installFetch(async (_url, init) => {
      seen.push(readHeader(init, 'X-Request-ID'));
      return envelopeResponse({});
    });

    await api.get('/items');
    await api.get('/items');

    expect(seen).toHaveLength(2);
    expect(seen[0]).toMatch(UUID_SHAPE);
    expect(seen[1]).toMatch(UUID_SHAPE);
    expect(seen[0]).not.toBe(seen[1]);
  });

  it('reuses the same X-Request-ID on the 401-refresh retry (backlog #126)', async () => {
    setAccessToken('expired-token');
    const itemRequestIds: (string | undefined)[] = [];
    installFetch(async (url, init) => {
      if (url.includes('/auth/refresh')) {
        return envelopeResponse({ accessToken: 'fresh-456' });
      }
      itemRequestIds.push(readHeader(init, 'X-Request-ID'));
      if (readHeader(init, 'Authorization') !== 'Bearer fresh-456') {
        return errorResponse(401, 'unauthorized');
      }
      return envelopeResponse({ ok: true });
    });

    const res = await api.get('/items');

    expect((res.data as any).data.ok).toBe(true);
    // First attempt + retry: same logical request, same correlation id.
    expect(itemRequestIds).toHaveLength(2);
    expect(itemRequestIds[0]).toMatch(UUID_SHAPE);
    expect(itemRequestIds[1]).toBe(itemRequestIds[0]);
  });

  it('never overwrites a caller-supplied X-Request-ID (backlog #126)', async () => {
    let seen: string | undefined;
    installFetch(async (_url, init) => {
      seen = readHeader(init, 'X-Request-ID');
      return envelopeResponse({});
    });

    await api.get('/items', {
      headers: { 'X-Request-ID': 'caller-supplied-123' },
    });
    expect(seen).toBe('caller-supplied-123');
  });

  it('keeps the shared 30s timeout as the client default (backlog #251)', () => {
    expect(REQUEST_TIMEOUT_MS).toBe(30_000);
    expect(api.defaults.timeout).toBe(REQUEST_TIMEOUT_MS);
    expect(api.defaults.baseURL).toBe('/api');
    expect(api.defaults.withCredentials).toBe(true);
  });

  it('maps a timed-out (never-resolving) request to the timeout copy (backlog #251)', async () => {
    // A never-resolving fetch double plus a short per-request timeout
    // exercises the real deadline path — the mapping, not a fake
    // adapter shape, is what this suite observes. The double emulates
    // real fetch by rejecting on abort, or the deadline could never
    // settle the request.
    installFetch(async (_url, init) => {
      return new Promise((_resolve, reject) => {
        init.signal?.addEventListener('abort', () => {
          const err = new Error('This operation was aborted');
          err.name = 'AbortError';
          reject(err);
        });
      });
    });

    let caught: unknown;
    await api.get('/items', { timeout: 50 }).catch((err) => {
      caught = err;
    });

    expect(isRequestTimeout(caught)).toBe(true);
    expect(describeRequestError(caught, 'fallback')).toBe(
      REQUEST_TIMEOUT_MESSAGE,
    );
  });

  it('does not map a caller-aborted request to the timeout copy', async () => {
    const controller = new AbortController();
    installFetch(async (_url, init) => {
      // Emulate real fetch: reject with AbortError when the signal fires.
      return new Promise((_resolve, reject) => {
        init.signal?.addEventListener('abort', () => {
          const err = new Error('This operation was aborted');
          err.name = 'AbortError';
          reject(err);
        });
      });
    });

    let caught: unknown;
    const pending = api
      .get('/items', { signal: controller.signal })
      .catch((err) => {
        caught = err;
      });
    controller.abort();
    await pending;

    expect(isRequestTimeout(caught)).toBe(false);
    expect((caught as ApiError).code).toBe('ERR_CANCELED');
  });

  it('leaves normal requests and envelope errors untouched by the timeout path (backlog #251)', async () => {
    installFetch(async (url) => {
      if (url.includes('/boom')) {
        return errorResponse(500, 'psp timeout');
      }
      return envelopeResponse({ ok: true });
    });

    const res = await api.get('/items');
    expect((res.data as any).data.ok).toBe(true);

    let caught: unknown;
    await api.get('/boom').catch((err) => {
      caught = err;
    });
    expect(isRequestTimeout(caught)).toBe(false);
    // An envelope message wins over the timeout copy — and over the
    // generic network copy, which is only for responses that never came.
    expect(describeRequestError(caught, 'fallback')).toBe('psp timeout');

    const networkErr = new ApiError('fetch failed');
    expect(isRequestTimeout(networkErr)).toBe(false);
    expect(describeRequestError(networkErr, 'fallback')).toBe(
      'Network error — is the backend running?',
    );
  });

  it('rejects without a second refresh when the retried request also 401s', async () => {
    setAccessToken('expired-token');
    let refreshCalls = 0;
    installFetch(async (url) => {
      if (url.includes('/auth/refresh')) {
        refreshCalls++;
        return envelopeResponse({ accessToken: 'still-bad' });
      }
      return errorResponse(401, 'unauthorized');
    });

    await expect(api.get('/items')).rejects.toThrow('unauthorized');
    expect(refreshCalls).toBe(1); // retry guard: no refresh retry loop
  });

  it('exposes ApiError with status/data/headers and the isApiError guard (backlog #288)', async () => {
    installFetch(async () => {
      const res = errorResponse(409, 'already ordered');
      res.headers = new Headers({ 'Retry-After': '30' });
      return res;
    });

    let caught: unknown;
    await api.get('/items').catch((err) => {
      caught = err;
    });

    expect(isApiError(caught)).toBe(true);
    expect(caught).toBeInstanceOf(ApiError);
    expect((caught as ApiError).response?.status).toBe(409);
    expect(
      ((caught as ApiError).response?.data as { message?: string })?.message,
    ).toBe('already ordered');
    expect((caught as ApiError).response?.headers.get('Retry-After')).toBe(
      '30',
    );
    expect(isApiError(new Error('plain'))).toBe(false);
  });

  it('serializes params into the query string and drops null/undefined', async () => {
    let seenUrl = '';
    installFetch(async (url) => {
      seenUrl = url;
      return envelopeResponse({});
    });

    await api.get('/items', {
      params: { q: 'bike', page: 0, size: 20, mine: undefined, tag: null },
    });
    expect(seenUrl).toBe('/api/items?q=bike&page=0&size=20');
  });

  it('JSON-encodes object bodies and passes FormData through untouched', async () => {
    const seen: { body: unknown; contentType: string | null }[] = [];
    installFetch(async (_url, init) => {
      seen.push({
        body: init.body,
        contentType: readHeader(init, 'Content-Type') ?? null,
      });
      return envelopeResponse({});
    });

    await api.post('/items', { title: 'bike', priceCents: 100 });
    expect(seen[0].body).toBe(JSON.stringify({ title: 'bike', priceCents: 100 }));
    expect(seen[0].contentType).toBe('application/json');

    const form = new FormData();
    form.append('file', new File(['x'], 'photo.png'));
    await api.post('/items/1/photo', form);
    expect(seen[1].body).toBe(form);
    // No manual Content-Type: the browser sets the multipart boundary.
    expect(seen[1].contentType).toBeNull();
  });

  it('sends a delete body from config.data (account close)', async () => {
    let seenBody: unknown;
    installFetch(async (_url, init) => {
      seenBody = init.body;
      return envelopeResponse({});
    });
    await api.delete('/auth/me', { data: { password: 'secret' } });
    expect(seenBody).toBe(JSON.stringify({ password: 'secret' }));
  });

  it('returns a Blob for responseType blob (account-data export)', async () => {
    installFetch(async () => ({
      ok: true,
      status: 200,
      statusText: 'OK',
      headers: new Headers({
        'Content-Disposition': 'attachment; filename="export.json"',
      }),
      text: async () => '{"a":1}',
      json: async () => ({ a: 1 }),
      blob: async () => new Blob(['{"a":1}']),
    }));

    const res = await api.get('/users/me/export', { responseType: 'blob' });
    expect(res.data).toBeInstanceOf(Blob);
    expect(res.headers.get('Content-Disposition')).toContain('export.json');
  });
});
