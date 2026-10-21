import { describe, expect, it } from 'vitest';

import {
  canonicalizePath,
  checkApiDrift,
  extractApiCalls,
  specOperations,
  templateToPath,
  toFullApiPath,
} from './check-api-drift.mjs';

// Fixture sources and specs only — the script's pure functions never
// touch the filesystem, so the extraction and matching rules are
// pinned against synthetic module text shaped like the real api
// modules (chained `api` newline `.get<...>(...)`, template-literal
// URLs, doc comments documenting each endpoint).

const spec = {
  paths: {
    '/api/items': { get: {}, post: {} },
    '/api/items/{id}': { get: {}, patch: {} },
    '/api/items/{id}/photo': { post: {} },
    '/api/auth/refresh': { post: {} },
    '/api/notifications/stream': { get: {} },
    '/api/users/{id}/block': { post: {}, delete: {} },
  },
};

describe('templateToPath + toFullApiPath', () => {
  it('turns a template interpolation into a named path param', () => {
    expect(templateToPath('/items/${id}')).toBe('/items/{id}');
    expect(templateToPath('/items/${input.itemId}/photo')).toBe('/items/{itemId}/photo');
  });

  it('unwraps encodeURIComponent and dotted expressions to the leaf name', () => {
    expect(templateToPath('/auth/sessions/${encodeURIComponent(id)}')).toBe(
      '/auth/sessions/{id}',
    );
    expect(templateToPath('/disputes/${input.disputeId}/evidence/${input.evidenceId}')).toBe(
      '/disputes/{disputeId}/evidence/{evidenceId}',
    );
  });

  it('strips the query string (the SSE ticket is not part of the path)', () => {
    expect(templateToPath('/api/notifications/stream?ticket=${encodeURIComponent(t)}')).toBe(
      '/api/notifications/stream',
    );
  });

  it('prefixes the shared instance baseURL and keeps full paths as-is', () => {
    expect(toFullApiPath('/items')).toBe('/api/items');
    expect(toFullApiPath('/api/auth/refresh')).toBe('/api/auth/refresh');
  });
});

describe('canonicalizePath', () => {
  it('compares param segments by position, not by name', () => {
    expect(canonicalizePath('/api/users/{id}/block')).toBe('/api/users/{}/block');
    expect(canonicalizePath('/api/users/{userId}/block')).toBe('/api/users/{}/block');
    expect(canonicalizePath('/api/items/{id}/photo')).not.toBe(
      canonicalizePath('/api/items/photo/{id}'),
    );
  });
});

describe('extractApiCalls', () => {
  it('extracts the chained multiline form used by the api modules', () => {
    const source = `
      queryFn: () =>
        api
          .get<ApiResponse<Page<Item>>>('/items', { params: { page } })
          .then((res) => res.data.data),
    `;
    expect(extractApiCalls(source)).toEqual({
      calls: [{ method: 'GET', path: '/api/items' }],
      unextractable: 0,
    });
  });

  it('extracts single-line calls with template URLs', () => {
    const source = 'api.get<ApiResponse<Item>>(`/items/${id}`)';
    expect(extractApiCalls(source).calls).toEqual([
      { method: 'GET', path: '/api/items/{id}' },
    ]);
  });

  it('extracts the raw axios refresh call in client.ts (full /api path)', () => {
    const source = "axios.post('/api/auth/refresh', {}, { withCredentials: true })";
    expect(extractApiCalls(source).calls).toEqual([
      { method: 'POST', path: '/api/auth/refresh' },
    ]);
  });

  it('counts the EventSource stream as a GET and drops its query', () => {
    const source =
      'new EventSource(`/api/notifications/stream?ticket=${encodeURIComponent(ticket)}`)';
    expect(extractApiCalls(source).calls).toEqual([
      { method: 'GET', path: '/api/notifications/stream' },
    ]);
  });

  it('never mistakes doc comments for calls', () => {
    const source = `
      /**
       * GET /api/items/{id} — the detail read.
       * A fake call in prose: api.get('/items/fake-comment-path')
       */
      export const x = 1; // api.post('/items/also-fake')
    `;
    expect(extractApiCalls(source)).toEqual({ calls: [], unextractable: 0 });
  });

  it('flags a non-literal URL as unextractable instead of skipping it', () => {
    const source = 'api.get(dynamicUrl)';
    expect(extractApiCalls(source)).toEqual({ calls: [], unextractable: 1 });
  });

  it('ignores non-call axios helpers', () => {
    const source = 'axios.isAxiosError(error); axios.create({ baseURL: \'/api\' })';
    expect(extractApiCalls(source)).toEqual({ calls: [], unextractable: 0 });
  });
});

describe('specOperations', () => {
  it('keeps only HTTP method keys, canonicalised by path', () => {
    const ops = specOperations(spec);
    expect(ops.get('/api/items')).toEqual(['GET', 'POST']);
    expect(ops.get('/api/items/{}')).toEqual(['GET', 'PATCH']);
  });
});

describe('checkApiDrift', () => {
  it('passes on an exact method+path match', () => {
    const result = checkApiDrift([{ method: 'GET', path: '/api/items' }], spec);
    expect(result).toEqual({ ok: true, checked: 1, violations: [] });
  });

  it('normalises {id} in the spec against ${...} names from the frontend', () => {
    const { calls } = extractApiCalls('api.post(`/users/${userId}/block`)');
    const result = checkApiDrift(calls, spec);
    expect(result.ok).toBe(true);
    expect(result.checked).toBe(1);
  });

  it('fails when the frontend calls a path the spec does not have', () => {
    const result = checkApiDrift([{ method: 'GET', path: '/api/items/{id}/videos' }], spec);
    expect(result.ok).toBe(false);
    expect(result.violations).toHaveLength(1);
    expect(result.violations[0]).toContain('missing path');
    expect(result.violations[0]).toContain('/api/items/{id}/videos');
  });

  it('fails when the path exists but the method does not', () => {
    const result = checkApiDrift([{ method: 'DELETE', path: '/api/items/{id}' }], spec);
    expect(result.ok).toBe(false);
    expect(result.violations).toHaveLength(1);
    expect(result.violations[0]).toContain('wrong method');
    expect(result.violations[0]).toContain('GET, PATCH');
  });

  it('collapses duplicate calls before checking', () => {
    const result = checkApiDrift(
      [
        { method: 'GET', path: '/api/items/{id}' },
        { method: 'GET', path: '/api/items/{itemId}' },
      ],
      spec,
    );
    expect(result).toEqual({ ok: true, checked: 1, violations: [] });
  });
});
