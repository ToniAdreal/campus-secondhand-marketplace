import { describe, expect, it } from 'vitest';
import { throttleWaitCopy } from './authThrottle';

function failure(status: number, headers?: unknown, message = 'too many requests') {
  return {
    isAxiosError: true,
    response: { status, headers, data: { code: status, message, data: null } },
  };
}

describe('throttleWaitCopy (backlog #242)', () => {
  it('renders the wait copy from a capitalised Retry-After header', () => {
    expect(throttleWaitCopy(failure(429, { 'Retry-After': '45' }))).toBe(
      'Too many attempts — try again in 45 seconds',
    );
  });

  it('renders the wait copy from a lowercase retry-after header', () => {
    expect(throttleWaitCopy(failure(429, { 'retry-after': '60' }))).toBe(
      'Too many attempts — try again in 60 seconds',
    );
  });

  it('reads AxiosHeaders-style get() case-insensitively', () => {
    const headers = { get: (name: string) => (name === 'retry-after' ? '30' : undefined) };
    expect(throttleWaitCopy(failure(429, headers))).toBe(
      'Too many attempts — try again in 30 seconds',
    );
  });

  it('uses the singular for one second', () => {
    expect(throttleWaitCopy(failure(429, { 'Retry-After': '1' }))).toBe(
      'Too many attempts — try again in 1 second',
    );
  });

  it('returns null for a non-429 status even with the header', () => {
    expect(throttleWaitCopy(failure(401, { 'Retry-After': '45' }))).toBeNull();
  });

  it('returns null when the header is missing', () => {
    expect(throttleWaitCopy(failure(429))).toBeNull();
  });

  it('returns null for a non-numeric or negative header', () => {
    expect(throttleWaitCopy(failure(429, { 'Retry-After': 'soon' }))).toBeNull();
    expect(throttleWaitCopy(failure(429, { 'Retry-After': '-5' }))).toBeNull();
  });

  it('returns null for non-Axios errors', () => {
    expect(throttleWaitCopy(new Error('boom'))).toBeNull();
    expect(throttleWaitCopy(null)).toBeNull();
  });
});
