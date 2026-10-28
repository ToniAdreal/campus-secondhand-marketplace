import { describe, it, expect, beforeEach } from 'vitest';
import axios from 'axios';
import {
  REQUEST_TIMEOUT_MESSAGE,
  REQUEST_TIMEOUT_MS,
  api,
  describeRequestError,
  getAccessToken,
  isRequestTimeout,
  setAccessToken,
} from './client';

// Minimal fake adapter: lets us script HTTP behavior without a backend.
function installAdapter(handler: (config: any) => Promise<any>) {
  (api.defaults as any).adapter = handler;
  (axios.defaults as any).adapter = handler;
}

function ok(config: any, data: any = {}) {
  return { data, status: 200, statusText: 'OK', headers: {}, config };
}

function httpError(config: any, status: number, message: string) {
  const err: any = new Error(message);
  err.config = config;
  err.response = { status };
  return err;
}

/** The exact shape Axios rejects with when its timeout fires. */
function timeoutError(config: any) {
  const err: any = new Error(`timeout of ${REQUEST_TIMEOUT_MS}ms exceeded`);
  err.config = config;
  err.code = 'ECONNABORTED';
  return err;
}

function readAuth(config: any): string | undefined {
  const h = config.headers;
  return typeof h?.get === 'function' ? h.get('Authorization') : h?.Authorization;
}

function readRequestId(config: any): string | undefined {
  const h = config.headers;
  return typeof h?.get === 'function'
    ? h.get('X-Request-ID')
    : h?.['X-Request-ID'] ?? h?.['x-request-id'];
}

const UUID_SHAPE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

describe('api client', () => {
  beforeEach(() => {
    setAccessToken(null);
  });

  it('attaches the in-memory access token as a Bearer header', async () => {
    setAccessToken('tok-123');
    let seen: string | undefined;
    installAdapter(async (config) => {
      seen = readAuth(config);
      return ok(config);
    });

    await api.get('/items');
    expect(seen).toBe('Bearer tok-123');
  });

  it('issues exactly one refresh for concurrent 401s and retries every request', async () => {
    setAccessToken('expired-token');
    let refreshCalls = 0;
    let itemCalls = 0;

    installAdapter(async (config) => {
      if (config.url?.includes('/auth/refresh')) {
        refreshCalls++;
        // hold the refresh in flight so the three 401s overlap
        await new Promise((r) => setTimeout(r, 20));
        return ok(config, { data: { accessToken: 'fresh-456' } });
      }
      itemCalls++;
      if (readAuth(config) !== 'Bearer fresh-456') {
        throw httpError(config, 401, 'unauthorized');
      }
      return ok(config, { ok: true });
    });

    const results = await Promise.all([
      api.get('/items'),
      api.get('/items'),
      api.get('/items'),
    ]);

    expect(refreshCalls).toBe(1); // single-flight, not one per failed request
    expect(itemCalls).toBe(6); // 3 initial + 3 retried with the fresh token
    expect(results.every((r) => r.data.ok)).toBe(true);
    expect(getAccessToken()).toBe('fresh-456');
  });

  it('clears the token and rejects when refresh fails', async () => {
    setAccessToken('expired-token');
    installAdapter(async (config) => {
      if (config.url?.includes('/auth/refresh')) {
        throw httpError(config, 401, 'refresh expired');
      }
      throw httpError(config, 401, 'unauthorized');
    });

    await expect(api.get('/items')).rejects.toThrow('unauthorized');
    expect(getAccessToken()).toBeNull();
  });

  it('attempts a fresh refresh for a later 401 wave after a failed refresh', async () => {
    setAccessToken('expired-token');
    let refreshCalls = 0;
    installAdapter(async (config) => {
      if (config.url?.includes('/auth/refresh')) {
        refreshCalls++;
        if (refreshCalls === 1) {
          throw httpError(config, 500, 'refresh backend down');
        }
        return ok(config, { data: { accessToken: 'recovered-789' } });
      }
      if (readAuth(config) !== 'Bearer recovered-789') {
        throw httpError(config, 401, 'unauthorized');
      }
      return ok(config, { ok: true });
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
    expect(res.data.ok).toBe(true);
    expect(getAccessToken()).toBe('recovered-789');
  });

  it('does not touch the refresh queue for non-401 errors', async () => {
    setAccessToken('tok-123');
    let refreshCalls = 0;
    installAdapter(async (config) => {
      if (config.url?.includes('/auth/refresh')) {
        refreshCalls++;
        return ok(config, { data: { accessToken: 'x' } });
      }
      throw httpError(config, 403, 'forbidden');
    });

    await expect(api.get('/items')).rejects.toThrow('forbidden');
    expect(refreshCalls).toBe(0);
    expect(getAccessToken()).toBe('tok-123'); // token left alone
  });

  it('stamps a UUID-shaped X-Request-ID on a plain request (backlog #126)', async () => {
    let seen: string | undefined;
    installAdapter(async (config) => {
      seen = readRequestId(config);
      return ok(config);
    });

    await api.get('/items');
    expect(seen).toMatch(UUID_SHAPE);
  });

  it('gives two separate calls distinct X-Request-IDs (backlog #126)', async () => {
    const seen: (string | undefined)[] = [];
    installAdapter(async (config) => {
      seen.push(readRequestId(config));
      return ok(config);
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
    installAdapter(async (config) => {
      if (config.url?.includes('/auth/refresh')) {
        return ok(config, { data: { accessToken: 'fresh-456' } });
      }
      itemRequestIds.push(readRequestId(config));
      if (readAuth(config) !== 'Bearer fresh-456') {
        throw httpError(config, 401, 'unauthorized');
      }
      return ok(config, { ok: true });
    });

    const res = await api.get('/items');

    expect(res.data.ok).toBe(true);
    // First attempt + retry: same logical request, same correlation id.
    expect(itemRequestIds).toHaveLength(2);
    expect(itemRequestIds[0]).toMatch(UUID_SHAPE);
    expect(itemRequestIds[1]).toBe(itemRequestIds[0]);
  });

  it('never overwrites a caller-supplied X-Request-ID (backlog #126)', async () => {
    let seen: string | undefined;
    installAdapter(async (config) => {
      seen = readRequestId(config);
      return ok(config);
    });

    await api.get('/items', {
      headers: { 'X-Request-ID': 'caller-supplied-123' },
    });
    expect(seen).toBe('caller-supplied-123');
  });

  it('sets the shared 30s timeout on the instance (backlog #251)', () => {
    expect(REQUEST_TIMEOUT_MS).toBe(30_000);
    expect(api.defaults.timeout).toBe(REQUEST_TIMEOUT_MS);
  });

  it('maps a timed-out (never-resolving) request to the timeout copy (backlog #251)', async () => {
    // A fake adapter bypasses Axios's own timeout timer, so the test
    // throws the ECONNABORTED shape Axios produces when that timer
    // fires on a never-resolving request — the mapping, not the timer,
    // is what this suite can observe.
    installAdapter(async (config) => {
      throw timeoutError(config);
    });

    let caught: unknown;
    await api.get('/items').catch((err) => {
      caught = err;
    });

    expect(isRequestTimeout(caught)).toBe(true);
    expect(describeRequestError(caught, 'fallback')).toBe(
      REQUEST_TIMEOUT_MESSAGE,
    );
  });

  it('leaves normal requests and envelope errors untouched by the timeout path (backlog #251)', async () => {
    installAdapter(async (config) => {
      if (config.url?.includes('/boom')) {
        const err: any = new Error('server blew up');
        err.isAxiosError = true;
        err.config = config;
        err.response = {
          status: 500,
          data: { code: 500, message: 'psp timeout', data: null },
        };
        throw err;
      }
      return ok(config, { ok: true });
    });

    const res = await api.get('/items');
    expect(res.data.ok).toBe(true);

    let caught: unknown;
    await api.get('/boom').catch((err) => {
      caught = err;
    });
    expect(isRequestTimeout(caught)).toBe(false);
    // An envelope message wins over the timeout copy — and over the
    // generic network copy, which is only for responses that never came.
    expect(describeRequestError(caught, 'fallback')).toBe('psp timeout');

    const networkErr: any = new Error('Network Error');
    networkErr.isAxiosError = true;
    networkErr.config = {};
    expect(isRequestTimeout(networkErr)).toBe(false);
    expect(describeRequestError(networkErr, 'fallback')).toBe(
      'Network error — is the backend running?',
    );
  });

  it('rejects without a second refresh when the retried request also 401s', async () => {
    setAccessToken('expired-token');
    let refreshCalls = 0;
    installAdapter(async (config) => {
      if (config.url?.includes('/auth/refresh')) {
        refreshCalls++;
        return ok(config, { data: { accessToken: 'still-bad' } });
      }
      throw httpError(config, 401, 'unauthorized');
    });

    await expect(api.get('/items')).rejects.toThrow('unauthorized');
    expect(refreshCalls).toBe(1); // _retry guard: no refresh retry loop
  });
});
