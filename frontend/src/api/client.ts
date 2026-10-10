import axios, { AxiosInstance, AxiosRequestConfig } from 'axios';

/**
 * Single Axios instance for the whole app.
 *
 * - The access token lives in memory (module scope, never localStorage).
 * - The refresh token lives in an httpOnly cookie, so the browser sends it
 *   automatically with `withCredentials`.
 * - On 401, exactly one refresh request is in flight; concurrent failed
 *   requests queue on the same promise instead of each triggering a refresh.
 * - Every outgoing request carries an `X-Request-ID` (backlog #126): the
 *   backend's RequestIdFilter (#44) echoes it and puts it in the MDC, so a
 *   user quoting the id from a failed request can be correlated with the
 *   exact backend log lines. A caller-supplied id is never overwritten,
 *   and a 401-refresh retry reuses the original request's id (it is the
 *   same logical request), while separate actions get distinct ids.
 *
 * NOTE: POST /api/auth/refresh is served by AuthController (httpOnly cookie,
 * token rotation). The queueing logic is unit-tested via a fake adapter, so it
 * needs no backend to verify.
 */

let accessToken: string | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function getAccessToken() {
  return accessToken;
}

let refreshPromise: Promise<string | null> | null = null;

function refreshAccessToken(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = axios
      .post('/api/auth/refresh', {}, { withCredentials: true })
      .then((res) => {
        const token: string | null = res.data?.data?.accessToken ?? null;
        setAccessToken(token);
        return token;
      })
      .catch(() => {
        setAccessToken(null);
        return null;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

export const api: AxiosInstance = axios.create({
  baseURL: '/api',
  withCredentials: true,
});

export const REQUEST_ID_HEADER = 'X-Request-ID';

/** Reads a header from either an AxiosHeaders instance or a plain object. */
function readHeader(headers: any, name: string): string | undefined {
  if (!headers) {
    return undefined;
  }
  if (typeof headers.get === 'function') {
    const value = headers.get(name);
    return value == null ? undefined : String(value);
  }
  return headers[name] ?? headers[name.toLowerCase()];
}

api.interceptors.request.use((config) => {
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
  // Stamp a correlation id unless the caller already supplied one. The
  // 401-refresh retry re-enters this interceptor via api(original): the
  // original id is still on the config then, so it is preserved rather
  // than replaced with a second id for the same logical request.
  if (!readHeader(config.headers, REQUEST_ID_HEADER)) {
    const requestId = crypto.randomUUID();
    if (typeof (config.headers as any)?.set === 'function') {
      (config.headers as any).set(REQUEST_ID_HEADER, requestId);
    } else {
      config.headers = {
        ...(config.headers as any),
        [REQUEST_ID_HEADER]: requestId,
      } as any;
    }
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const original = error.config as AxiosRequestConfig & { _retry?: boolean };
    const isRefreshCall = original.url?.includes('/auth/refresh');
    if (error.response?.status === 401 && !original._retry && !isRefreshCall) {
      original._retry = true;
      const token = await refreshAccessToken();
      if (token) {
        // Preserve the original X-Request-ID explicitly: the retry is the
        // same logical request, so it must correlate to the same backend
        // log lines as the failed first attempt (the request interceptor
        // would otherwise only keep it if the spread happened to).
        const requestId = readHeader(original.headers, REQUEST_ID_HEADER);
        original.headers = {
          ...original.headers,
          Authorization: `Bearer ${token}`,
          ...(requestId ? { [REQUEST_ID_HEADER]: requestId } : {}),
        };
        return api(original);
      }
    }
    return Promise.reject(error);
  },
);
