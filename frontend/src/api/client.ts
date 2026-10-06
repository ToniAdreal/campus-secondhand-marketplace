import axios, { AxiosInstance, AxiosRequestConfig } from 'axios';

/**
 * Single Axios instance for the whole app.
 *
 * - The access token lives in memory (module scope, never localStorage).
 * - The refresh token lives in an httpOnly cookie, so the browser sends it
 *   automatically with `withCredentials`.
 * - On 401, exactly one refresh request is in flight; concurrent failed
 *   requests queue on the same promise instead of each triggering a refresh.
 *
 * NOTE: POST /api/auth/refresh does not exist yet — the auth endpoints are a
 * later roadmap step. The queueing logic is written first so the behavior is
 * unit-testable without a backend.
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

api.interceptors.request.use((config) => {
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`;
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
        original.headers = { ...original.headers, Authorization: `Bearer ${token}` };
        return api(original);
      }
    }
    return Promise.reject(error);
  },
);
