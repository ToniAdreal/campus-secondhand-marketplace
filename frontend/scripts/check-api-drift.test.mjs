import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { describe, expect, it } from 'vitest';

import {
  canonicalizePath,
  checkApiDrift,
  checkFieldMap,
  checkResponseData,
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

// Shape pinning (backlog #258). The synthetic document deliberately
// keeps the pre-#271 shape where it matters: the pay operation
// carries NO 2xx — its ApiResponse<OrderDto> envelope rides on the
// 403 — because the controller's explicit @ApiResponses used to list
// error codes only. That fixture now pins the no-2xx fallback path;
// the #271 block below covers the 2xx-preferred collection on a
// document where the 200 carries its own envelope.

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

// Shape pinning, third tranche (backlog #271): the gate prefers the
// 2xx carriers, and register/login pin one variant per success status.

const ORDER_FULL = {
  type: 'object',
  properties: {
    id: { type: 'integer' },
    status: { type: 'string', enum: ['PENDING', 'PAID', 'CANCELLED'] },
    amountCents: { type: 'integer' },
  },
};

function twoXxSpec(successSchema, errorSchema) {
  return {
    paths: {
      '/api/orders/{id}/pay': {
        post: {
          responses: {
            200: {
              content: {
                '*/*': { schema: { $ref: '#/components/schemas/ApiResponseOrderSuccess' } },
              },
            },
            403: {
              content: {
                '*/*': { schema: { $ref: '#/components/schemas/ApiResponseOrderError' } },
              },
            },
          },
        },
      },
    },
    components: {
      schemas: {
        ApiResponseOrderSuccess: {
          type: 'object',
          properties: {
            code: { type: 'integer' },
            message: { type: 'string' },
            data: { $ref: '#/components/schemas/OrderSuccess' },
          },
        },
        OrderSuccess: successSchema,
        ApiResponseOrderError: {
          type: 'object',
          properties: {
            code: { type: 'integer' },
            message: { type: 'string' },
            data: { $ref: '#/components/schemas/OrderError' },
          },
        },
        OrderError: errorSchema,
      },
    },
  };
}

const REGISTER_USER_FIELDS = {
  id: 'integer',
  username: 'string',
  email: 'string',
  roles: { type: 'array', items: 'string' },
};

function registerDoc(pendingProperties) {
  return {
    paths: {
      '/api/auth/register': {
        post: {
          requestBody: {
            content: {
              'application/json': {
                schema: {
                  type: 'object',
                  properties: {
                    username: { type: 'string' },
                    email: { type: 'string' },
                    password: { type: 'string' },
                  },
                },
              },
            },
          },
          responses: {
            200: {
              content: {
                'application/json': { schema: { $ref: '#/components/schemas/ApiResponseAuth' } },
              },
            },
            201: {
              content: {
                'application/json': {
                  schema: {
                    type: 'object',
                    properties: {
                      code: { type: 'integer' },
                      message: { type: 'string' },
                      data: { $ref: '#/components/schemas/RegisterPending' },
                    },
                  },
                },
              },
            },
          },
        },
      },
    },
    components: {
      schemas: {
        ApiResponseAuth: {
          type: 'object',
          properties: {
            code: { type: 'integer' },
            message: { type: 'string' },
            data: { $ref: '#/components/schemas/Auth' },
          },
        },
        Auth: {
          type: 'object',
          properties: {
            accessToken: { type: 'string' },
            tokenType: { type: 'string' },
            expiresInSeconds: { type: 'integer' },
            user: { $ref: '#/components/schemas/AuthUser' },
          },
        },
        AuthUser: {
          type: 'object',
          properties: {
            id: { type: 'integer' },
            username: { type: 'string' },
            email: { type: 'string' },
            roles: { type: 'array', items: { type: 'string' } },
          },
        },
        RegisterPending: { type: 'object', properties: pendingProperties },
      },
    },
  };
}

const REGISTER_PIN = {
  method: 'POST',
  path: '/api/auth/register',
  request: { username: 'string', email: 'string', password: 'string' },
  responseByStatus: {
    200: {
      accessToken: 'string',
      tokenType: 'string',
      expiresInSeconds: 'integer',
      user: { type: 'object', fields: REGISTER_USER_FIELDS },
    },
    201: {
      user: { type: 'object', fields: REGISTER_USER_FIELDS },
      emailVerificationRequired: 'boolean',
    },
  },
};

describe('2xx-preferred response collection + responseByStatus (backlog #271)', () => {
  it('fails when a success-only field disappears from the 200 schema even while an error response still carries the old DTO', () => {
    const { properties, ...rest } = ORDER_FULL;
    const { status, ...withoutStatus } = properties;
    const doc = twoXxSpec({ ...rest, properties: withoutStatus }, ORDER_FULL);
    const result = checkShapeDrift(
      [{ method: 'POST', path: '/api/orders/{id}/pay', response: ORDER_PIN }],
      doc,
    );
    expect(result.ok).toBe(false);
    expect(result.violations.join(' ')).toMatch(/status/);
  });

  it('passes when the 200 carries every pinned field even if the error response drops one', () => {
    const { properties, ...rest } = ORDER_FULL;
    const { status, ...withoutStatus } = properties;
    const doc = twoXxSpec(ORDER_FULL, { ...rest, properties: withoutStatus });
    expect(
      checkShapeDrift(
        [{ method: 'POST', path: '/api/orders/{id}/pay', response: ORDER_PIN }],
        doc,
      ).ok,
    ).toBe(true);
  });

  it('pins each register success variant against its own status', () => {
    const doc = registerDoc({
      user: { $ref: '#/components/schemas/AuthUser' },
      emailVerificationRequired: { type: 'boolean' },
    });
    const result = checkShapeDrift([REGISTER_PIN], doc);
    expect(result.ok).toBe(true);
    expect(result.responsePinned).toBe(1);
    expect(result.unpinnedResponses).toEqual([]);
  });

  it('fails when a variant field disappears from its own status schema', () => {
    const doc = registerDoc({ user: { $ref: '#/components/schemas/AuthUser' } });
    const result = checkShapeDrift([REGISTER_PIN], doc);
    expect(result.ok).toBe(false);
    expect(result.violations.join(' ')).toMatch(/201.*emailVerificationRequired/);
  });

  it('fails when a pinned status is absent from the document', () => {
    const doc = registerDoc({
      user: { $ref: '#/components/schemas/AuthUser' },
      emailVerificationRequired: { type: 'boolean' },
    });
    const pin = {
      ...REGISTER_PIN,
      responseByStatus: { 202: { challenge: 'string' } },
    };
    const result = checkShapeDrift([pin], doc);
    expect(result.ok).toBe(false);
    expect(result.violations.join(' ')).toMatch(/no 202 response/);
  });
});

// Shape pinning, second tranche (backlog #264): the items / messages /
// notifications / reviews surfaces add two response forms the #258
// field-map form cannot express — bare-array data (suggestions,
// similar, price-history, preferences) and bare-scalar data (the
// unread-count and read-all counts). Both are pinned through
// checkResponseData, which checkShapeDrift now uses for every pin.

const ITEM_SCHEMA = {
  type: 'object',
  properties: {
    id: { type: 'integer' },
    title: { type: 'string' },
    priceCents: { type: 'integer' },
  },
};

function tranche264Spec(itemSchema = ITEM_SCHEMA, countType = 'integer') {
  return {
    paths: {
      '/api/items/{id}': {
        get: {
          responses: {
            200: {
              content: { '*/*': { schema: { $ref: '#/components/schemas/ApiResponseItem' } } },
            },
          },
        },
      },
      '/api/items/suggestions': {
        get: {
          responses: {
            200: {
              content: { '*/*': { schema: { $ref: '#/components/schemas/ApiResponseSuggestions' } } },
            },
          },
        },
      },
      '/api/messages/unread-count': {
        get: {
          responses: {
            200: {
              content: { '*/*': { schema: { $ref: '#/components/schemas/ApiResponseCount' } } },
            },
          },
        },
      },
    },
    components: {
      schemas: {
        ApiResponseItem: {
          type: 'object',
          properties: {
            code: { type: 'integer' },
            message: { type: 'string' },
            data: { $ref: '#/components/schemas/Item' },
          },
        },
        Item: itemSchema,
        ApiResponseSuggestions: {
          type: 'object',
          properties: {
            code: { type: 'integer' },
            message: { type: 'string' },
            data: { type: 'array', items: { $ref: '#/components/schemas/ItemSuggestion' } },
          },
        },
        ItemSuggestion: {
          type: 'object',
          properties: { id: { type: 'integer' }, title: { type: 'string' } },
        },
        ApiResponseCount: {
          type: 'object',
          properties: {
            code: { type: 'integer' },
            message: { type: 'string' },
            data: { type: countType },
          },
        },
      },
    },
  };
}

const SUGGESTION_ARRAY_PIN = {
  type: 'array',
  items: { type: 'object', fields: { id: 'integer', title: 'string' } },
};

describe('checkResponseData (#264 array + scalar forms)', () => {
  it('passes a bare-array pin when every item carries the pinned fields', () => {
    const doc = tranche264Spec();
    const operation = operationByMethodPath(doc, 'GET', '/api/items/suggestions');
    const [candidate] = responseDataSchemas(operation, doc);
    expect(checkResponseData(SUGGESTION_ARRAY_PIN, candidate.dataSchema, doc)).toEqual([]);
  });

  it('fails a bare-array pin when an item field disappears or changes type', () => {
    const doc = tranche264Spec();
    doc.components.schemas.ItemSuggestion = {
      type: 'object',
      properties: { id: { type: 'integer' } },
    };
    const operation = operationByMethodPath(doc, 'GET', '/api/items/suggestions');
    const [candidate] = responseDataSchemas(operation, doc);
    expect(checkResponseData(SUGGESTION_ARRAY_PIN, candidate.dataSchema, doc).join(' ')).toMatch(
      /title/,
    );
    const mistyped = tranche264Spec();
    mistyped.components.schemas.ItemSuggestion.properties.id = { type: 'string' };
    const mistypedOp = operationByMethodPath(mistyped, 'GET', '/api/items/suggestions');
    const [mistypedCandidate] = responseDataSchemas(mistypedOp, mistyped);
    expect(
      checkResponseData(SUGGESTION_ARRAY_PIN, mistypedCandidate.dataSchema, mistyped).join(' '),
    ).toMatch(/id/);
  });

  it('fails a bare-array pin when the data is an object, not a list', () => {
    const doc = tranche264Spec();
    const operation = operationByMethodPath(doc, 'GET', '/api/items/{id}');
    const [candidate] = responseDataSchemas(operation, doc);
    expect(
      checkResponseData(SUGGESTION_ARRAY_PIN, candidate.dataSchema, doc).join(' '),
    ).toMatch(/pinned array/);
  });

  it('passes a scalar pin on a bare count, fails when the count changes type', () => {
    const doc = tranche264Spec();
    const operation = operationByMethodPath(doc, 'GET', '/api/messages/unread-count');
    const [candidate] = responseDataSchemas(operation, doc);
    expect(checkResponseData('integer', candidate.dataSchema, doc)).toEqual([]);
    const stringCount = tranche264Spec(ITEM_SCHEMA, 'string');
    const stringOp = operationByMethodPath(stringCount, 'GET', '/api/messages/unread-count');
    const [stringCandidate] = responseDataSchemas(stringOp, stringCount);
    expect(checkResponseData('integer', stringCandidate.dataSchema, stringCount).join(' ')).toMatch(
      /type mismatch/,
    );
  });

  it('still treats a field map pinning a field named "type" as an object map', () => {
    const doc = {
      paths: {
        '/api/notifications': {
          get: {
            responses: {
              200: {
                content: { '*/*': { schema: { $ref: '#/components/schemas/ApiResponseNotif' } } },
              },
            },
          },
        },
      },
      components: {
        schemas: {
          ApiResponseNotif: {
            type: 'object',
            properties: {
              code: { type: 'integer' },
              message: { type: 'string' },
              data: { $ref: '#/components/schemas/Notification' },
            },
          },
          Notification: {
            type: 'object',
            properties: {
              id: { type: 'integer' },
              type: { type: 'string', enum: ['ORDER_PAID', 'ORDER_SHIPPED'] },
            },
          },
        },
      },
    };
    const pin = {
      id: 'integer',
      type: { type: 'string', enum: ['ORDER_PAID', 'ORDER_SHIPPED'] },
    };
    const operation = operationByMethodPath(doc, 'GET', '/api/notifications');
    const [candidate] = responseDataSchemas(operation, doc);
    expect(checkResponseData(pin, candidate.dataSchema, doc)).toEqual([]);
    expect(
      checkShapeDrift([{ method: 'GET', path: '/api/notifications', response: pin }], doc).ok,
    ).toBe(true);
  });

  it('pins multipart request fields across the body schema + query parameters', () => {
    const doc = {
      paths: {
        '/api/messages/photo': {
          post: {
            parameters: [
              { name: 'itemId', in: 'query', schema: { type: 'integer' } },
              { name: 'receiverId', in: 'query', schema: { type: 'integer' } },
              { name: 'body', in: 'query', schema: { type: 'string' } },
            ],
            requestBody: {
              content: {
                'multipart/form-data': {
                  schema: {
                    type: 'object',
                    properties: { file: { type: 'string', format: 'binary' } },
                  },
                },
              },
            },
            responses: {
              200: {
                content: { '*/*': { schema: { $ref: '#/components/schemas/ApiResponseMsg' } } },
              },
            },
          },
        },
      },
      components: {
        schemas: {
          ApiResponseMsg: {
            type: 'object',
            properties: {
              code: { type: 'integer' },
              message: { type: 'string' },
              data: { $ref: '#/components/schemas/Message' },
            },
          },
          Message: {
            type: 'object',
            properties: { id: { type: 'integer' }, body: { type: 'string' } },
          },
        },
      },
    };
    const pin = {
      method: 'POST',
      path: '/api/messages/photo',
      request: { itemId: 'integer', receiverId: 'integer', body: 'string', file: 'string' },
      response: { id: 'integer', body: 'string' },
    };
    expect(checkShapeDrift([pin], doc).ok).toBe(true);
    const dropped = structuredClone(doc);
    dropped.paths['/api/messages/photo'].post.parameters =
      dropped.paths['/api/messages/photo'].post.parameters.filter((p) => p.name !== 'body');
    expect(checkShapeDrift([pin], dropped).ok).toBe(false);
    expect(checkShapeDrift([pin], dropped).violations.join(' ')).toMatch(/body/);
  });

  it('runs array + scalar pins end to end through checkShapeDrift', () => {
    const doc = tranche264Spec();
    expect(
      checkShapeDrift(
        [
          { method: 'GET', path: '/api/items/suggestions', response: SUGGESTION_ARRAY_PIN },
          { method: 'GET', path: '/api/messages/unread-count', response: 'integer' },
        ],
        doc,
      ).ok,
    ).toBe(true);
    const drifted = tranche264Spec();
    drifted.components.schemas.ItemSuggestion.properties.title = { type: 'integer' };
    expect(
      checkShapeDrift(
        [{ method: 'GET', path: '/api/items/suggestions', response: SUGGESTION_ARRAY_PIN }],
        drifted,
      ).ok,
    ).toBe(false);
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

// End-to-end fixture for the #264 tranche: the same script, driven
// against an items-surface document and an items pin, must fail the
// job when a frontend-read item field disappears and pass clean.
describe('shape gate CLI fixture (#264 tranche)', () => {
  function runItemsGate(doc, pinFields) {
    const dir = mkdtempSync(join(tmpdir(), 'api-drift-items-fixture-'));
    try {
      const apiDir = join(dir, 'api');
      const specPath = join(dir, 'openapi.json');
      const pinsPath = join(dir, 'pins.mjs');
      mkdirSync(apiDir, { recursive: true });
      writeFileSync(
        join(apiDir, 'items.ts'),
        "export const detail = (id) => api.get(`/items/${id}`);\n",
      );
      writeFileSync(specPath, JSON.stringify(doc));
      writeFileSync(
        pinsPath,
        `export const API_SHAPE_PINS = [{ method: 'GET', path: '/api/items/{id}', response: ${JSON.stringify(pinFields)} }];\n`,
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

  it('fails the job when an item field disappears, passes on a clean document', () => {
    const itemPin = { id: 'integer', title: 'string', priceCents: 'integer' };
    const drifted = tranche264Spec({
      type: 'object',
      properties: { id: { type: 'integer' }, priceCents: { type: 'integer' } },
    });
    const failed = runItemsGate(drifted, itemPin);
    expect(failed.status).toBe(1);
    expect(`${failed.stdout}\n${failed.stderr}`).toMatch(/API SHAPE DRIFT/);
    expect(`${failed.stdout}\n${failed.stderr}`).toMatch(/title/);
    const passed = runItemsGate(tranche264Spec(), itemPin);
    expect(passed.status).toBe(0);
    expect(passed.stdout).toMatch(/No API shape drift/);
  });
});

// Shape pinning, third tranche (backlog #274): offers + seller
// payouts + disputes. The fixture document mirrors the post-#274
// backend: every annotated action carries its own bare 200 envelope
// (the pre-#274 error-only @ApiResponses are gone), the evidence
// upload is multipart with only the `file` part in the body schema,
// and the evidence list answers a bare array in the envelope.

const OFFER_SCHEMA = {
  type: 'object',
  properties: {
    id: { type: 'integer' },
    itemId: { type: 'integer' },
    buyerId: { type: 'integer' },
    sellerId: { type: 'integer' },
    priceCents: { type: 'integer' },
    counterPriceCents: { type: 'integer' },
    status: {
      type: 'string',
      enum: ['PENDING', 'COUNTERED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'WITHDRAWN'],
    },
    orderId: { type: 'integer' },
    expiresAt: { type: 'string' },
    createdAt: { type: 'string' },
  },
};

const PAYOUT_SCHEMA = {
  type: 'object',
  properties: {
    id: { type: 'integer' },
    orderId: { type: 'integer' },
    sellerId: { type: 'integer' },
    amountCents: { type: 'integer' },
    adjustmentsCents: { type: 'integer' },
    netCents: { type: 'integer' },
    status: { type: 'string', enum: ['PENDING', 'PAYABLE', 'PAID'] },
    createdAt: { type: 'string' },
    payableAt: { type: 'string' },
    paidAt: { type: 'string' },
  },
};

const PAYOUT_TOTALS_SCHEMA = {
  type: 'object',
  properties: {
    pendingCount: { type: 'integer' },
    pendingCents: { type: 'integer' },
    payableCount: { type: 'integer' },
    payableCents: { type: 'integer' },
    paidCount: { type: 'integer' },
    paidCents: { type: 'integer' },
    adjustmentsCents: { type: 'integer' },
    netCents: { type: 'integer' },
  },
};

const DISPUTE_SCHEMA = {
  type: 'object',
  properties: {
    id: { type: 'integer' },
    orderId: { type: 'integer' },
    buyerId: { type: 'integer' },
    sellerId: { type: 'integer' },
    reason: { type: 'string' },
    status: { type: 'string', enum: ['OPEN', 'RESOLVED_REFUND', 'RESOLVED_RELEASE'] },
    previousStatus: { type: 'string', enum: ['PAID', 'COMPLETED'] },
    listingRestored: { type: 'boolean' },
    createdAt: { type: 'string' },
    resolvedAt: { type: 'string' },
    resolutionNote: { type: 'string' },
  },
};

const EVIDENCE_SCHEMA = {
  type: 'object',
  properties: {
    id: { type: 'integer' },
    disputeId: { type: 'integer' },
    uploaderId: { type: 'integer' },
    url: { type: 'string' },
    createdAt: { type: 'string' },
  },
};

function tranche274Spec(overrides = {}) {
  const schemas = {
    ApiResponseOffer: {
      type: 'object',
      properties: {
        code: { type: 'integer' },
        message: { type: 'string' },
        data: { $ref: '#/components/schemas/Offer' },
      },
    },
    Offer: overrides.offer ?? OFFER_SCHEMA,
    ApiResponsePagePayout: {
      type: 'object',
      properties: {
        code: { type: 'integer' },
        message: { type: 'string' },
        data: { $ref: '#/components/schemas/PagePayout' },
      },
    },
    PagePayout: {
      type: 'object',
      properties: {
        content: { type: 'array', items: { $ref: '#/components/schemas/Payout' } },
        totalElements: { type: 'integer' },
        totalPages: { type: 'integer' },
        number: { type: 'integer' },
      },
    },
    Payout: overrides.payout ?? PAYOUT_SCHEMA,
    ApiResponseTotals: {
      type: 'object',
      properties: {
        code: { type: 'integer' },
        message: { type: 'string' },
        data: { $ref: '#/components/schemas/PayoutTotals' },
      },
    },
    PayoutTotals: overrides.totals ?? PAYOUT_TOTALS_SCHEMA,
    ApiResponseDispute: {
      type: 'object',
      properties: {
        code: { type: 'integer' },
        message: { type: 'string' },
        data: { $ref: '#/components/schemas/Dispute' },
      },
    },
    Dispute: overrides.dispute ?? DISPUTE_SCHEMA,
    ApiResponseListEvidence: {
      type: 'object',
      properties: {
        code: { type: 'integer' },
        message: { type: 'string' },
        data: { type: 'array', items: { $ref: '#/components/schemas/Evidence' } },
      },
    },
    ApiResponseEvidence: {
      type: 'object',
      properties: {
        code: { type: 'integer' },
        message: { type: 'string' },
        data: { $ref: '#/components/schemas/Evidence' },
      },
    },
    Evidence: overrides.evidence ?? EVIDENCE_SCHEMA,
    OfferCreate: {
      type: 'object',
      properties: { priceCents: { type: 'integer' } },
    },
    DisputeCreate: {
      type: 'object',
      properties: { reason: { type: 'string' } },
    },
    DisputeResolve: {
      type: 'object',
      properties: { resolutionNote: { type: 'string' } },
    },
  };
  const envelope = (name) => ({
    content: { '*/*': { schema: { $ref: `#/components/schemas/${name}` } } },
  });
  return {
    paths: {
      '/api/items/{itemId}/offers': {
        post: {
          requestBody: {
            content: { 'application/json': { schema: { $ref: '#/components/schemas/OfferCreate' } } },
          },
          responses: { 200: envelope('ApiResponseOffer'), 422: envelope('ApiResponseOffer') },
        },
      },
      '/api/offers/{id}/accept': {
        post: { responses: { 200: envelope('ApiResponseOffer'), 422: envelope('ApiResponseOffer') } },
      },
      '/api/seller/payouts': {
        get: { responses: { 200: envelope('ApiResponsePagePayout') } },
      },
      '/api/seller/payouts/totals': {
        get: { responses: { 200: envelope('ApiResponseTotals') } },
      },
      '/api/orders/{id}/dispute': {
        post: {
          requestBody: {
            content: { 'application/json': { schema: { $ref: '#/components/schemas/DisputeCreate' } } },
          },
          responses: { 200: envelope('ApiResponseDispute'), 422: envelope('ApiResponseDispute') },
        },
      },
      '/api/disputes/{disputeId}/evidence': {
        get: { responses: { 200: envelope('ApiResponseListEvidence') } },
        post: {
          requestBody: {
            content: {
              'multipart/form-data': {
                schema: {
                  type: 'object',
                  properties: { file: { type: 'string', format: 'binary' } },
                },
              },
            },
          },
          responses: { 200: envelope('ApiResponseEvidence'), 422: envelope('ApiResponseEvidence') },
        },
      },
      '/api/admin/disputes/{id}/resolve-refund': {
        post: {
          requestBody: {
            content: { 'application/json': { schema: { $ref: '#/components/schemas/DisputeResolve' } } },
          },
          responses: { 200: envelope('ApiResponseDispute'), 422: envelope('ApiResponseDispute') },
        },
      },
    },
    components: { schemas },
  };
}

const TRANCHE_274_PINS = [
  {
    method: 'POST',
    path: '/api/items/{itemId}/offers',
    request: { priceCents: 'integer' },
    response: {
      id: 'integer',
      itemId: 'integer',
      buyerId: 'integer',
      sellerId: 'integer',
      priceCents: 'integer',
      counterPriceCents: 'integer',
      status: {
        type: 'string',
        enum: ['PENDING', 'COUNTERED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'WITHDRAWN'],
      },
      orderId: 'integer',
      expiresAt: 'string',
      createdAt: 'string',
    },
  },
  {
    method: 'POST',
    path: '/api/offers/{id}/accept',
    response: { id: 'integer', status: 'string', priceCents: 'integer' },
  },
  {
    method: 'GET',
    path: '/api/seller/payouts',
    response: {
      content: {
        type: 'array',
        items: {
          type: 'object',
          fields: { id: 'integer', netCents: 'integer', status: 'string' },
        },
      },
      totalElements: 'integer',
      totalPages: 'integer',
      number: 'integer',
    },
  },
  {
    method: 'GET',
    path: '/api/seller/payouts/totals',
    response: { pendingCents: 'integer', payableCents: 'integer', paidCents: 'integer', netCents: 'integer' },
  },
  {
    method: 'POST',
    path: '/api/orders/{id}/dispute',
    request: { reason: 'string' },
    response: { id: 'integer', reason: 'string', status: 'string', resolutionNote: 'string' },
  },
  {
    method: 'GET',
    path: '/api/disputes/{disputeId}/evidence',
    response: { type: 'array', items: { type: 'object', fields: { id: 'integer', url: 'string' } } },
  },
  {
    method: 'POST',
    path: '/api/disputes/{disputeId}/evidence',
    request: { file: 'string' },
    response: { id: 'integer', disputeId: 'integer', url: 'string' },
  },
  {
    method: 'POST',
    path: '/api/admin/disputes/{id}/resolve-refund',
    request: { resolutionNote: 'string' },
    response: { id: 'integer', status: 'string', resolutionNote: 'string' },
  },
];

describe('shape pins, third tranche (backlog #274)', () => {
  it('pins offers, payouts and disputes end to end through checkShapeDrift', () => {
    const result = checkShapeDrift(TRANCHE_274_PINS, tranche274Spec());
    expect(result.ok).toBe(true);
    expect(result.violations).toEqual([]);
  });

  it('fails when a payout field disappears from the totals or a row', () => {
    const { netCents, ...totalsWithoutNet } = PAYOUT_TOTALS_SCHEMA.properties;
    const droppedTotals = checkShapeDrift(TRANCHE_274_PINS, tranche274Spec({
      totals: { ...PAYOUT_TOTALS_SCHEMA, properties: totalsWithoutNet },
    }));
    expect(droppedTotals.ok).toBe(false);
    expect(droppedTotals.violations.join(' ')).toMatch(/netCents/);
    const { amountCents, ...payoutWithoutAmount } = PAYOUT_SCHEMA.properties;
    const droppedRow = checkShapeDrift(
      [{ method: 'GET', path: '/api/seller/payouts', response: { content: { type: 'array', items: { type: 'object', fields: { id: 'integer', amountCents: 'integer' } } }, totalElements: 'integer', totalPages: 'integer', number: 'integer' } }],
      tranche274Spec({ payout: { ...PAYOUT_SCHEMA, properties: payoutWithoutAmount } }),
    );
    expect(droppedRow.ok).toBe(false);
    expect(droppedRow.violations.join(' ')).toMatch(/amountCents/);
  });

  it('fails when a dispute field disappears, even while the error response still carries it', () => {
    // The 200 and the 422 carry the same envelope component here, so
    // dropping the field from the shared Dispute schema drops it from
    // both: the gate must still fail on the 200, not pass on any
    // carrier that happens to keep it.
    const { resolutionNote, ...disputeWithoutNote } = DISPUTE_SCHEMA.properties;
    const result = checkShapeDrift(TRANCHE_274_PINS, tranche274Spec({
      dispute: { ...DISPUTE_SCHEMA, properties: disputeWithoutNote },
    }));
    expect(result.ok).toBe(false);
    expect(result.violations.join(' ')).toMatch(/resolutionNote/);
  });

  it('applies the #271 2xx preference to the offer accept: a success-only field lost from the 200 fails even while the error response still carries it', () => {
    const { counterPriceCents, ...offerWithoutCounter } = OFFER_SCHEMA.properties;
    const acceptDoc = (successSchema, errorSchema) => ({
      paths: {
        '/api/offers/{id}/accept': {
          post: {
            responses: {
              200: {
                content: { '*/*': { schema: { $ref: '#/components/schemas/ApiResponseOfferOk' } } },
              },
              422: {
                content: { '*/*': { schema: { $ref: '#/components/schemas/ApiResponseOfferErr' } } },
              },
            },
          },
        },
      },
      components: {
        schemas: {
          ApiResponseOfferOk: {
            type: 'object',
            properties: {
              code: { type: 'integer' },
              message: { type: 'string' },
              data: { $ref: '#/components/schemas/OfferOk' },
            },
          },
          OfferOk: successSchema,
          ApiResponseOfferErr: {
            type: 'object',
            properties: {
              code: { type: 'integer' },
              message: { type: 'string' },
              data: { $ref: '#/components/schemas/OfferErr' },
            },
          },
          OfferErr: errorSchema,
        },
      },
    });
    const acceptPin = {
      method: 'POST',
      path: '/api/offers/{id}/accept',
      response: { id: 'integer', counterPriceCents: 'integer', orderId: 'integer' },
    };
    const droppedFrom200 = acceptDoc(
      { ...OFFER_SCHEMA, properties: offerWithoutCounter },
      OFFER_SCHEMA,
    );
    const failed = checkShapeDrift([acceptPin], droppedFrom200);
    expect(failed.ok).toBe(false);
    expect(failed.violations.join(' ')).toMatch(/counterPriceCents/);
    const droppedFromErrorOnly = acceptDoc(
      OFFER_SCHEMA,
      { ...OFFER_SCHEMA, properties: offerWithoutCounter },
    );
    expect(checkShapeDrift([acceptPin], droppedFromErrorOnly).ok).toBe(true);
  });
});

describe('shape gate CLI fixture (#274 tranche)', () => {
  function runPayoutsGate(doc, pinFields) {
    const dir = mkdtempSync(join(tmpdir(), 'api-drift-payouts-fixture-'));
    try {
      const apiDir = join(dir, 'api');
      const specPath = join(dir, 'openapi.json');
      const pinsPath = join(dir, 'pins.mjs');
      mkdirSync(apiDir, { recursive: true });
      writeFileSync(
        join(apiDir, 'payouts.ts'),
        "export const totals = () => api.get('/seller/payouts/totals');\n",
      );
      writeFileSync(specPath, JSON.stringify(doc));
      writeFileSync(
        pinsPath,
        `export const API_SHAPE_PINS = [{ method: 'GET', path: '/api/seller/payouts/totals', response: ${JSON.stringify(pinFields)} }];\n`,
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

  it('fails the job when a payout totals field disappears, passes on a clean document', () => {
    const totalsPin = { pendingCents: 'integer', netCents: 'integer' };
    const { netCents, ...totalsWithoutNet } = PAYOUT_TOTALS_SCHEMA.properties;
    const drifted = tranche274Spec({
      totals: { ...PAYOUT_TOTALS_SCHEMA, properties: totalsWithoutNet },
    });
    const failed = runPayoutsGate(drifted, totalsPin);
    expect(failed.status).toBe(1);
    expect(`${failed.stdout}\n${failed.stderr}`).toMatch(/API SHAPE DRIFT/);
    expect(`${failed.stdout}\n${failed.stderr}`).toMatch(/netCents/);
    const passed = runPayoutsGate(tranche274Spec(), totalsPin);
    expect(passed.status).toBe(0);
    expect(passed.stdout).toMatch(/No API shape drift/);
  });
});
