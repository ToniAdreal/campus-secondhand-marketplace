/**
 * Single fetch-based HTTP client for the whole app (backlog #288: the
 * axios -> fetch migration, slices 1-4 — the public surface below keeps
 * the shape the api modules and pages already consumed: method helpers
 * returning { data, status, statusText, headers, config }, and errors
 * carrying a `response` object with { status, data, headers }).
 *
 * - The access token lives in memory (module scope, never localStorage).
 * - The refresh token lives in an httpOnly cookie, so the browser sends it
 *   automatically with `credentials: 'include'`.
 * - On 401, exactly one refresh request is in flight; concurrent failed
 *   requests queue on the same promise instead of each triggering a refresh.
 * - Every outgoing request carries an `X-Request-ID` (backlog #126): the
 *   backend's RequestIdFilter (#44) echoes it and puts it in the MDC, so a
 *   user quoting the id from a failed request can be correlated with the
 *   exact backend log lines. A caller-supplied id is never overwritten,
 *   and a 401-refresh retry reuses the original request's id (it is the
 *   same logical request), while separate actions get distinct ids.
 * - Every request shares one timeout (backlog #251): a hung backend used
 *   to leave every page spinning forever. REQUEST_TIMEOUT_MS bounds that
 *   wait; a timed-out request rejects with an ApiError carrying code
 *   `ECONNABORTED` (the timeout code callers already key on), which
 *   describeRequestError maps to REQUEST_TIMEOUT_MESSAGE through the
 *   same envelope path pages already render (envelope message, then
 *   the no-response network copy, then the caller's fallback) — no
 *   second copy channel. Callers that need real cancellation (not just
 *   a deadline) pass their own AbortSignal per request, e.g. a page
 *   aborting on unmount; the timeout is the backstop, not a substitute.
 *
 * NOTE: POST /api/auth/refresh is served by AuthController (httpOnly cookie,
 * token rotation). The queueing logic is unit-tested via a stubbed global
 * fetch, so it needs no backend to verify.
 */

const API_BASE_URL = '/api';

let accessToken: string | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function getAccessToken() {
  return accessToken;
}

let refreshPromise: Promise<string | null> | null = null;

/** Shared request deadline (backlog #251): 30 seconds. Per-request
 *  overrides stay possible via the request `timeout` option, but no call
 *  site may silently drop the deadline — a hung backend must surface
 *  as a timeout error, never as an endless spinner. */
export const REQUEST_TIMEOUT_MS = 30_000;

/** Copy for a timed-out request, surfaced via describeRequestError. */
export const REQUEST_TIMEOUT_MESSAGE =
  'The request timed out. Please check your connection and try again.';

export const REQUEST_ID_HEADER = 'X-Request-ID';

/** Per-request options, mirroring the config keys the api modules
 *  actually use today: query params, headers, an abort signal, a delete
 *  body, a blob response (the account-data export), and a timeout.
 *  Headers stay a plain record (every call site passes one); the client
 *  still accepts a Headers instance at runtime via toHeaders. */
export interface ApiRequestConfig {
  params?: Record<
    string,
    string | number | boolean | null | undefined | Array<string | number | boolean>
  >;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  data?: unknown;
  responseType?: 'json' | 'blob' | 'text';
  timeout?: number;
}

/** Success shape: the response shape the api modules consume. */
export interface ApiResponse<T = unknown> {
  data: T;
  status: number;
  statusText: string;
  headers: Headers;
  config: ApiRequestConfig & { method: string; url: string };
}

/** Error shape: the error shape the pages consume — `response`
 *  is present for HTTP failures and absent for network/timeout/cancel
 *  failures. */
export interface ApiErrorResponse {
  status: number;
  data: unknown;
  headers: Headers;
}

export class ApiError extends Error {
  /** Brand checked by isApiError (and by duck-typed test doubles). */
  readonly isApiError = true;
  readonly response?: ApiErrorResponse;
  readonly config?: ApiRequestConfig & { method: string; url: string };
  /** `ECONNABORTED` for a timeout, `ERR_CANCELED` for a caller abort. */
  readonly code?: string;

  constructor(
    message: string,
    opts: {
      response?: ApiErrorResponse;
      config?: ApiRequestConfig & { method: string; url: string };
      code?: string;
    } = {},
  ) {
    super(message);
    this.name = 'ApiError';
    this.response = opts.response;
    this.config = opts.config;
    this.code = opts.code;
  }
}

/** True for errors thrown by this client. It also accepts plain doubles
 *  carrying the brand plus a response, so tests can fake one
 *  without constructing the class. */
export function isApiError(error: unknown): error is ApiError {
  if (error instanceof ApiError) return true;
  const candidate = error as { isApiError?: unknown; response?: unknown } | null;
  return (
    !!candidate &&
    candidate.isApiError === true &&
    typeof candidate === 'object' &&
    'response' in candidate
  );
}

/** True when the failure is this client aborting a request on its timeout. */
export function isRequestTimeout(error: unknown): boolean {
  return (
    (error as { code?: unknown } | null)?.code === 'ECONNABORTED' &&
    !(error as { response?: unknown } | null)?.response
  );
}

/**
 * Maps a failed request to the copy pages render, through the existing
 * envelope path: a timeout gets its own copy first (it has no response,
 * so the envelope branch below can never fire for it), then a backend
 * envelope message verbatim, then the no-response network copy, then
 * the caller's fallback. Status-specific branches (401/409/429 …) stay
 * in the caller's own helper, which delegates the remainder here.
 * The response is read duck-typed (not only via isApiError).
 */
export function describeRequestError(error: unknown, fallback: string): string {
  if (isRequestTimeout(error)) return REQUEST_TIMEOUT_MESSAGE;
  const response = (error as { response?: { data?: { message?: unknown } } } | null)
    ?.response;
  if (response) {
    const message = response.data?.message;
    if (typeof message === 'string' && message) return message;
  }
  if (
    error instanceof ApiError ||
    (error as { isApiError?: unknown } | null)?.isApiError === true
  ) {
    if (!response) return 'Network error — is the backend running?';
  }
  return fallback;
}

/** Builds the fetch URL: base + path, plus a serialized query string.
 *  Null/undefined params are dropped; array values
 *  repeat the key. */
function buildUrl(
  url: string,
  params: ApiRequestConfig['params'],
): string {
  const path =
    url.startsWith('http://') || url.startsWith('https://')
      ? url
      : `${API_BASE_URL}${url.startsWith('/') ? url : `/${url}`}`;
  if (!params) return path;
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined) continue;
    if (Array.isArray(value)) {
      for (const item of value) query.append(key, String(item));
    } else {
      query.append(key, String(value));
    }
  }
  const serialized = query.toString();
  if (!serialized) return path;
  return `${path}${path.includes('?') ? '&' : '?'}${serialized}`;
}

function toHeaders(input: ApiRequestConfig['headers']): Headers {
  if (!input) return new Headers();
  if (input instanceof Headers) return new Headers(input);
  return new Headers(input);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return (
    typeof value === 'object' &&
    value !== null &&
    !Array.isArray(value) &&
    !(value instanceof FormData) &&
    !(value instanceof Blob) &&
    !(value instanceof URLSearchParams)
  );
}

function buildBody(
  method: string,
  data: unknown,
  headers: Headers,
): BodyInit | undefined {
  if (data === undefined) return undefined;
  if (method === 'GET') return undefined;
  if (data instanceof FormData || data instanceof Blob || typeof data === 'string') {
    // FormData carries its own multipart Content-Type + boundary; never
    // set one manually, or the boundary breaks.
    return data as BodyInit;
  }
  if (data instanceof URLSearchParams) return data;
  if (isPlainObject(data) || Array.isArray(data)) {
    if (!headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    return JSON.stringify(data);
  }
  return String(data);
}

/** One fetch attempt bounded by `timeoutMs` and the caller's signal.
 *  Throws a marker error with `timedOut: true` when the deadline fires,
 *  so the caller can map it to ECONNABORTED instead of a generic abort. */
async function fetchWithTimeout(
  url: string,
  init: RequestInit,
  timeoutMs: number,
  callerSignal?: AbortSignal,
): Promise<Response> {
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  const onCallerAbort = () => controller.abort(callerSignal?.reason);
  try {
    if (callerSignal) {
      if (callerSignal.aborted) {
        controller.abort(callerSignal.reason);
      } else {
        callerSignal.addEventListener('abort', onCallerAbort, { once: true });
      }
    }
    return await fetch(url, { ...init, signal: controller.signal });
  } catch (error) {
    if (timedOut) {
      const timeoutError = new Error(
        `timeout of ${timeoutMs}ms exceeded`,
      ) as Error & { timedOut: boolean };
      timeoutError.timedOut = true;
      throw timeoutError;
    }
    throw error;
  } finally {
    clearTimeout(timer);
    callerSignal?.removeEventListener('abort', onCallerAbort);
  }
}

async function parseBody(
  response: Response,
  responseType: ApiRequestConfig['responseType'],
): Promise<unknown> {
  if (responseType === 'blob') return response.blob();
  const text = await response.text();
  if (text === '') return null;
  if (responseType === 'text') return text;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function envelopeMessage(data: unknown): string | undefined {
  if (data && typeof data === 'object') {
    const message = (data as { message?: unknown }).message;
    if (typeof message === 'string' && message) return message;
  }
  return undefined;
}

function refreshAccessToken(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const response = await fetchWithTimeout(
          `${API_BASE_URL}/auth/refresh`,
          { method: 'POST', credentials: 'include' },
          REQUEST_TIMEOUT_MS,
        );
        // Any refresh failure (HTTP or network) clears the token, as the
        // previous client's catch did — a failed refresh means the session
        // is gone, and keeping the stale token would retry it forever.
        if (!response.ok) {
          setAccessToken(null);
          return null;
        }
        const body = await parseBody(response, 'json');
        const token =
          (body as { data?: { accessToken?: unknown } } | null)?.data
            ?.accessToken ?? null;
        setAccessToken(typeof token === 'string' ? token : null);
        return typeof token === 'string' ? token : null;
      } catch {
        setAccessToken(null);
        return null;
      }
    })().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

async function request<T>(
  method: string,
  url: string,
  config: ApiRequestConfig = {},
  data?: unknown,
): Promise<ApiResponse<T>> {
  const fullUrl = buildUrl(url, config.params);
  const headers = toHeaders(config.headers);
  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`);
  }
  // Stamp a correlation id unless the caller already supplied one. The
  // 401-refresh retry below reuses this same Headers object, so the
  // retry is the same logical request and keeps the original id.
  if (!headers.has(REQUEST_ID_HEADER)) {
    headers.set(REQUEST_ID_HEADER, crypto.randomUUID());
  }
  const body = buildBody(method, data ?? config.data, headers);
  const timeoutMs = config.timeout ?? REQUEST_TIMEOUT_MS;
  const requestConfig = { ...config, method, url: fullUrl };
  const isRefreshCall = fullUrl.includes('/auth/refresh');
  let retried = false;

  for (;;) {
    let response: Response;
    try {
      response = await fetchWithTimeout(
        fullUrl,
        {
          method,
          headers,
          body,
          credentials: 'include',
        },
        timeoutMs,
        config.signal,
      );
    } catch (error) {
      if ((error as { timedOut?: unknown } | null)?.timedOut === true) {
        throw new ApiError((error as Error).message, {
          config: requestConfig,
          code: 'ECONNABORTED',
        });
      }
      if ((error as { name?: unknown } | null)?.name === 'AbortError') {
        throw new ApiError('Request aborted', {
          config: requestConfig,
          code: 'ERR_CANCELED',
        });
      }
      throw new ApiError(
        error instanceof Error ? error.message : 'Network error',
        { config: requestConfig },
      );
    }

    const parsed = await parseBody(response, config.responseType);
    if (response.ok) {
      return {
        data: parsed as T,
        status: response.status,
        statusText: response.statusText,
        headers: response.headers,
        config: requestConfig,
      };
    }
    if (
      response.status === 401 &&
      !retried &&
      !isRefreshCall
    ) {
      retried = true;
      const token = await refreshAccessToken();
      if (token) {
        headers.set('Authorization', `Bearer ${token}`);
        continue;
      }
    }
    throw new ApiError(
      envelopeMessage(parsed) ?? `Request failed with status ${response.status}`,
      {
        response: {
          status: response.status,
          data: parsed,
          headers: response.headers,
        },
        config: requestConfig,
      },
    );
  }
}

/**
 * The shared client. Method helpers mirror the instance the api
 * modules call today (get/post/put/patch/delete with the same config
 * keys and the same response shape); `defaults` is a plain config
 * record kept for the one consumer that reads it
 * (auth.test.ts asserts withCredentials). vi.spyOn(api, 'post') in the
 * api-module tests keeps working because these are plain writable
 * object methods.
 *
 * The methods are declared on an interface with the exact generic
 * shape (<T, R = ApiResponse<T>, D>) rather than inferred from the
 * object literal: the test suites spy on these methods and this
 * interface shape is what keeps their deferred-resolve and
 * mockResolvedValueOnce(undefined) patterns type-checking (an inferred
 * object-literal signature makes those same tests fail under tsc).
 */
export interface ApiClient {
  defaults: {
    baseURL: string;
    withCredentials: boolean;
    timeout: number;
  };
  get<T = any, R = ApiResponse<T>>(
    url: string,
    config?: ApiRequestConfig,
  ): Promise<R>;
  post<T = any, R = ApiResponse<T>, D = any>(
    url: string,
    data?: D,
    config?: ApiRequestConfig,
  ): Promise<R>;
  put<T = any, R = ApiResponse<T>, D = any>(
    url: string,
    data?: D,
    config?: ApiRequestConfig,
  ): Promise<R>;
  patch<T = any, R = ApiResponse<T>, D = any>(
    url: string,
    data?: D,
    config?: ApiRequestConfig,
  ): Promise<R>;
  delete<T = any, R = ApiResponse<T>>(
    url: string,
    config?: ApiRequestConfig,
  ): Promise<R>;
}

export const api: ApiClient = {
  defaults: {
    baseURL: API_BASE_URL,
    withCredentials: true,
    timeout: REQUEST_TIMEOUT_MS,
  },
  get<T = any, R = ApiResponse<T>>(url: string, config?: ApiRequestConfig) {
    return request<T>('GET', url, config) as unknown as Promise<R>;
  },
  post<T = any, R = ApiResponse<T>, D = any>(
    url: string,
    data?: D,
    config?: ApiRequestConfig,
  ) {
    return request<T>('POST', url, config, data) as unknown as Promise<R>;
  },
  put<T = any, R = ApiResponse<T>, D = any>(
    url: string,
    data?: D,
    config?: ApiRequestConfig,
  ) {
    return request<T>('PUT', url, config, data) as unknown as Promise<R>;
  },
  patch<T = any, R = ApiResponse<T>, D = any>(
    url: string,
    data?: D,
    config?: ApiRequestConfig,
  ) {
    return request<T>('PATCH', url, config, data) as unknown as Promise<R>;
  },
  delete<T = any, R = ApiResponse<T>>(url: string, config?: ApiRequestConfig) {
    return request<T>('DELETE', url, config) as unknown as Promise<R>;
  },
};
