import axios from 'axios';

/**
 * Backlog #242: when AuthRateLimitFilter throttles an auth request it
 * answers 429 with a `Retry-After` header (seconds until the next token,
 * CORS-exposed via SecurityConfig) plus the usual {code,message,data}
 * envelope. A throttled user otherwise gets the generic failure copy and
 * retries blindly into the same wall — so the auth forms surface the
 * wait instead. Returns the wait copy only for a 429 carrying a usable
 * Retry-After; anything else returns null and the caller's existing error
 * mapping runs untouched. This deliberately reads only the existing
 * Retry-After header — it does not depend on the RateLimit headers
 * in #236.
 */
export function throttleWaitCopy(err: unknown): string | null {
  if (!axios.isAxiosError(err)) return null;
  if (err.response?.status !== 429) return null;
  const seconds = retryAfterSeconds(err.response.headers);
  if (seconds === null) return null;
  return `Too many attempts — try again in ${seconds} second${seconds === 1 ? '' : 's'}`;
}

function retryAfterSeconds(headers: unknown): number | null {
  if (!headers || typeof headers !== 'object') return null;
  let raw: unknown;
  const get = (headers as { get?: unknown }).get;
  if (typeof get === 'function') {
    // AxiosHeaders is case-insensitive; plain test doubles are not, so
    // fall back to both casings below when there is no get().
    raw = (get as (name: string) => unknown).call(headers, 'retry-after');
  } else {
    const record = headers as Record<string, unknown>;
    raw = record['retry-after'] ?? record['Retry-After'];
  }
  if (typeof raw !== 'string' && typeof raw !== 'number') return null;
  const seconds = Number(raw);
  if (!Number.isInteger(seconds) || seconds < 0) return null;
  return seconds;
}
