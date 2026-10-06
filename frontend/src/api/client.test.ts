import { describe, it, expect, beforeEach } from 'vitest';
import axios from 'axios';
import { api, setAccessToken, getAccessToken } from './client';

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

function readAuth(config: any): string | undefined {
  const h = config.headers;
  return typeof h?.get === 'function' ? h.get('Authorization') : h?.Authorization;
}

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
});
