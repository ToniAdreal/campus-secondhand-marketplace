import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { describe, expect, it } from 'vitest';

import {
  canonicalizePath,
  checkApiDrift,
  checkFieldMap,
  checkShapeDrift,
  extractApiCalls,
  operationByMethodPath,
  resolveSchema,
  responseDataSchemas,
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

// Shape pinning (backlog #258). The synthetic document mirrors the
// real one where it matters: the pay operation carries NO 2xx — its
// ApiResponse<OrderDto> envelope rides on the 403 — because the
// controller's explicit @ApiResponses lists error codes only.

const ORDER_SCHEMA = {
  type: 'object',
  properties: {
    id: { type: 'integer' },
    status: { type: 'string', enum: ['PENDING', 'PAID', 'CANCELLED'] },
    amountCents: { type: 'integer' },
    carrier: { type: 'string' },
  },
};

function shapeSpec(orderSchema = ORDER_SCHEMA, extraPaths = {}) {
  return {
    paths: {
      '/api/orders/{id}/pay': {
        post: {
          responses: {
            403: {
              content: { '*/*': { schema: { $ref: '#/components/schemas/ApiResponseOrder' } } },
            },
          },
        },
      },
      '/api/orders': {
        post: {
          requestBody: {
            content: {
              'application/json': { schema: { $ref: '#/components/schemas/OrderCreate' } },
            },
          },
          responses: {
            409: {
              content: { '*/*': { schema: { $ref: '#/components/schemas/ApiResponseOrder' } } },
            },
          },
        },
      },
      ...extraPaths,
    },
    components: {
      schemas: {
        ApiResponseOrder: {
          type: 'object',
          properties: {
            code: { type: 'integer' },
            message: { type: 'string' },
            data: { $ref: '#/components/schemas/Order' },
          },
        },
        Order: orderSchema,
        OrderCreate: {
          type: 'object',
          properties: { itemId: { type: 'integer' } },
        },
      },
    },
  };
}

const ORDER_PIN = {
  id: 'integer',
  status: { type: 'string', enum: ['PENDING', 'PAID', 'CANCELLED'] },
  amountCents: 'integer',
  carrier: { type: 'string', required: false },
};

describe('resolveSchema + responseDataSchemas', () => {
  it('resolves a $ref to the named component schema', () => {
    const doc = shapeSpec();
    expect(resolveSchema({ $ref: '#/components/schemas/Order' }, doc)).toEqual(ORDER_SCHEMA);
    expect(resolveSchema({ $ref: '#/components/schemas/Nope' }, doc)).toBeNull();
  });

  it('collects the envelope data schema from a non-2xx response', () => {
    const doc = shapeSpec();
    const operation = operationByMethodPath(doc, 'POST', '/api/orders/{id}/pay');
    expect(operation).not.toBeNull();
    const candidates = responseDataSchemas(operation, doc);
    expect(candidates).toHaveLength(1);
    expect(candidates[0].statusCode).toBe('403');
    expect(candidates[0].dataSchema).toEqual(ORDER_SCHEMA);
  });

  it('matches pin paths param-name-insensitively ({id} vs {orderId})', () => {
    const doc = shapeSpec();
    expect(operationByMethodPath(doc, 'POST', '/api/orders/{orderId}/pay')).not.toBeNull();
  });
});

describe('checkShapeDrift', () => {
  it('passes when the error-carried envelope carries every pinned field', () => {
    const result = checkShapeDrift(
      [{ method: 'POST', path: '/api/orders/{id}/pay', response: ORDER_PIN }],
      shapeSpec(),
    );
    expect(result.ok).toBe(true);
    expect(result.responsePinned).toBe(1);
    expect(result.violations).toEqual([]);
  });

  it('fails when a required response field disappears', () => {
    const { properties, ...rest } = ORDER_SCHEMA;
    const { status, ...kept } = properties;
    const doc = shapeSpec({ ...rest, properties: kept });
    const result = checkShapeDrift(
      [{ method: 'POST', path: '/api/orders/{id}/pay', response: ORDER_PIN }],
      doc,
    );
    expect(result.ok).toBe(false);
    expect(result.violations.join(' ')).toMatch(/status/);
  });

  it('fails when a pinned field changes type', () => {
    const doc = shapeSpec({
      ...ORDER_SCHEMA,
      properties: { ...ORDER_SCHEMA.properties, amountCents: { type: 'string' } },
    });
    const result = checkShapeDrift(
      [{ method: 'POST', path: '/api/orders/{id}/pay', response: ORDER_PIN }],
      doc,
    );
    expect(result.ok).toBe(false);
    expect(result.violations.join(' ')).toMatch(/amountCents.*string/);
  });

  it('fails when a pinned enum value is dropped, passes when the enum is absent', () => {
    const dropped = shapeSpec({
      ...ORDER_SCHEMA,
      properties: {
        ...ORDER_SCHEMA.properties,
        status: { type: 'string', enum: ['PENDING', 'PAID'] },
      },
    });
    expect(
      checkShapeDrift(
        [{ method: 'POST', path: '/api/orders/{id}/pay', response: ORDER_PIN }],
        dropped,
      ).ok,
    ).toBe(false);
    const noEnum = shapeSpec({
      ...ORDER_SCHEMA,
      properties: { ...ORDER_SCHEMA.properties, status: { type: 'string' } },
    });
    expect(
      checkShapeDrift(
        [{ method: 'POST', path: '/api/orders/{id}/pay', response: ORDER_PIN }],
        noEnum,
      ).ok,
    ).toBe(true);
  });

  it('passes when an optional field is absent, fails when it is mistyped', () => {
    const { properties, ...rest } = ORDER_SCHEMA;
    const { carrier, ...kept } = properties;
    const absent = shapeSpec({ ...rest, properties: kept });
    expect(
      checkShapeDrift(
        [{ method: 'POST', path: '/api/orders/{id}/pay', response: ORDER_PIN }],
        absent,
      ).ok,
    ).toBe(true);
    const mistyped = shapeSpec({
      ...ORDER_SCHEMA,
      properties: { ...ORDER_SCHEMA.properties, carrier: { type: 'integer' } },
    });
    expect(
      checkShapeDrift(
        [{ method: 'POST', path: '/api/orders/{id}/pay', response: ORDER_PIN }],
        mistyped,
      ).ok,
    ).toBe(false);
  });

  it('checks nested object and array-item shapes', () => {
    const doc = shapeSpec(ORDER_SCHEMA, {
      '/api/orders': {
        get: {
          responses: {
            200: {
              content: { '*/*': { schema: { $ref: '#/components/schemas/ApiResponsePage' } } },
            },
          },
        },
      },
    });
    doc.components.schemas.ApiResponsePage = {
      type: 'object',
      properties: {
        code: { type: 'integer' },
        message: { type: 'string' },
        data: { $ref: '#/components/schemas/Page' },
      },
    };
    doc.components.schemas.Page = {
      type: 'object',
      properties: {
        content: { type: 'array', items: { $ref: '#/components/schemas/Order' } },
        totalElements: { type: 'integer' },
      },
    };
    const pagePin = {
      content: { type: 'array', items: { type: 'object', fields: ORDER_PIN } },
      totalElements: 'integer',
    };
    expect(
      checkShapeDrift([{ method: 'GET', path: '/api/orders', response: pagePin }], doc).ok,
    ).toBe(true);
    const { properties, ...rest } = ORDER_SCHEMA;
    const { amountCents, ...kept } = properties;
    doc.components.schemas.Order = { ...rest, properties: kept };
    const failed = checkShapeDrift(
      [{ method: 'GET', path: '/api/orders', response: pagePin }],
      doc,
    );
    expect(failed.ok).toBe(false);
    expect(failed.violations.join(' ')).toMatch(/amountCents/);
  });

  it('fails when a pinned request field disappears or the body is absent', () => {
    const pin = {
      method: 'POST',
      path: '/api/orders',
      request: { itemId: 'integer' },
      response: ORDER_PIN,
    };
    expect(checkShapeDrift([pin], shapeSpec()).ok).toBe(true);
    const noBody = shapeSpec();
    delete noBody.paths['/api/orders'].post.requestBody;
    expect(checkShapeDrift([pin], noBody).ok).toBe(false);
    const noField = shapeSpec();
    noField.components.schemas.OrderCreate = { type: 'object', properties: {} };
    expect(checkShapeDrift([pin], noField).ok).toBe(false);
  });

  it('fails closed when a response-pinned operation has no typed response', () => {
    const doc = {
      paths: {
        '/api/auth/login': {
          post: {
            requestBody: {
              content: {
                'application/json': {
                  schema: {
                    type: 'object',
                    properties: { usernameOrEmail: { type: 'string' } },
                  },
                },
              },
            },
            responses: { 200: { content: { '*/*': { schema: { type: 'object' } } } } },
          },
        },
      },
      components: { schemas: {} },
    };
    const result = checkShapeDrift(
      [
        {
          method: 'POST',
          path: '/api/auth/login',
          request: { usernameOrEmail: 'string' },
          response: { accessToken: 'string' },
        },
      ],
      doc,
    );
    expect(result.ok).toBe(false);
    expect(result.violations.join(' ')).toMatch(/no typed response/);
  });

  it('never fails a pin without a response key for want of a response shape', () => {
    const doc = {
      paths: {
        '/api/auth/login': {
          post: {
            requestBody: {
              content: {
                'application/json': {
                  schema: {
                    type: 'object',
                    properties: { usernameOrEmail: { type: 'string' } },
                  },
                },
              },
            },
            responses: { 200: { content: { '*/*': { schema: { type: 'object' } } } } },
          },
        },
      },
      components: { schemas: {} },
    };
    const result = checkShapeDrift(
      [
        {
          method: 'POST',
          path: '/api/auth/login',
          request: { usernameOrEmail: 'string' },
          responseNote: 'ResponseEntity<?> — untyped',
        },
      ],
      doc,
    );
    expect(result.ok).toBe(true);
    expect(result.unpinnedResponses).toHaveLength(1);
  });

  it('fails when a pinned operation is absent from the document', () => {
    const result = checkShapeDrift(
      [{ method: 'GET', path: '/api/orders/{id}/receipt', response: { orderId: 'integer' } }],
      shapeSpec(),
    );
    expect(result.ok).toBe(false);
    expect(result.violations.join(' ')).toMatch(/not in the OpenAPI document/);
  });

  it('accepts TS number against integer or number in the document', () => {
    expect(checkFieldMap({ n: 'number' }, { type: 'object', properties: { n: { type: 'integer' } } }, {})).toEqual([]);
    expect(checkFieldMap({ n: 'number' }, { type: 'object', properties: { n: { type: 'number' } } }, {})).toEqual([]);
    expect(checkFieldMap({ n: 'number' }, { type: 'object', properties: { n: { type: 'string' } } }, {})).toHaveLength(1);
  });
});

// Job wiring (in the check-mutation-gate mould): the api-drift job
// must keep running the export step and this script, or the gate
// narrows silently.
function findRepoRoot() {
  let dir = process.cwd();
  for (;;) {
    if (existsSync(join(dir, '.github', 'workflows', 'ci.yml'))) return dir;
    const parent = dirname(dir);
    if (parent === dir) throw new Error('repo root not found above cwd');
    dir = parent;
  }
}

describe('api-drift job wiring', () => {
  it('ci.yml api-drift job exports the document and runs check:api', () => {
    const repoRoot = findRepoRoot();
    const ci = readFileSync(join(repoRoot, '.github', 'workflows', 'ci.yml'), 'utf8');
    const start = ci.indexOf('  api-drift:');
    expect(start).toBeGreaterThan(-1);
    const rest = ci.slice(start);
    const nextJob = rest.slice(1).search(/\n  [a-z0-9-]+:\n/);
    const job = nextJob === -1 ? rest : rest.slice(0, nextJob + 1);
    expect(job).toMatch(/OpenApiExportTest/);
    expect(job).toMatch(/check:api/);
  });
});

// End-to-end fixture: the script itself (not just its pure functions)
// must exit non-zero on a drifted document and zero on a clean one.
describe('shape gate CLI fixture', () => {
  function runGate(doc, pinFields) {
    const dir = mkdtempSync(join(tmpdir(), 'api-drift-fixture-'));
    try {
      const apiDir = join(dir, 'api');
      const specPath = join(dir, 'openapi.json');
      const pinsPath = join(dir, 'pins.mjs');
      mkdirSync(apiDir, { recursive: true });
      writeFileSync(
        join(apiDir, 'orders.ts'),
        "export const pay = (orderId) => api.post(`/orders/${orderId}/pay`, {});\n",
      );
      writeFileSync(specPath, JSON.stringify(doc));
      writeFileSync(
        pinsPath,
        `export const API_SHAPE_PINS = [{ method: 'POST', path: '/api/orders/{id}/pay', response: ${JSON.stringify(pinFields)} }];\n`,
      );
      const script = join(findRepoRoot(), 'frontend', 'scripts', 'check-api-drift.mjs');
      return spawnSync(
        process.execPath,
        [script, '--spec', specPath, '--api-dir', apiDir, '--pins', pinsPath],
        { encoding: 'utf8' },
      );
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }

  it('fails the job on a drifted document, passes on a clean one', () => {
    const drifted = shapeSpec({
      ...ORDER_SCHEMA,
      properties: {
        id: { type: 'integer' },
        amountCents: { type: 'integer' },
        carrier: { type: 'string' },
      },
    });
    const failed = runGate(drifted, ORDER_PIN);
    expect(failed.status).toBe(1);
    expect(`${failed.stdout}\n${failed.stderr}`).toMatch(/API SHAPE DRIFT/);
    expect(`${failed.stdout}\n${failed.stderr}`).toMatch(/status/);
    const passed = runGate(shapeSpec(), ORDER_PIN);
    expect(passed.status).toBe(0);
    expect(passed.stdout).toMatch(/No API shape drift/);
  });
});
