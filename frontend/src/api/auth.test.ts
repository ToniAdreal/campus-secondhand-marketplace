import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './client';
import { login, me } from './auth';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

const authBody = {
  accessToken: 'tok-abc',
  tokenType: 'Bearer',
  expiresInSeconds: 900,
  user: { id: 5, username: 'toni', email: 'toni@example.com', roles: ['USER'] },
};

describe('login', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('posts the credentials to /auth/login and returns the user + access token from the envelope', async () => {
    postSpy.mockResolvedValueOnce(envelope(authBody));

    const result = await login({ usernameOrEmail: 'toni', password: 'secret' });

    expect(postSpy).toHaveBeenCalledWith('/auth/login', {
      usernameOrEmail: 'toni',
      password: 'secret',
    });
    expect(result).toEqual({
      accessToken: 'tok-abc',
      user: { id: 5, username: 'toni', email: 'toni@example.com', roles: ['USER'] },
    });
    // the refresh token is NOT in the JSON body — it travels in the
    // httpOnly cookie, which JS must never see
    expect('refreshToken' in (result as object)).toBe(false);
  });

  it('propagates API errors so the caller can read the envelope message', async () => {
    const failure = {
      isAxiosError: true,
      response: {
        status: 401,
        data: { code: 401, message: 'invalid credentials', data: null },
      },
    };
    postSpy.mockRejectedValueOnce(failure);

    await expect(login({ usernameOrEmail: 'toni', password: 'wrong' })).rejects.toBe(failure);
  });

  it('the shared api instance sends credentials — the httpOnly refresh token is browser-managed', () => {
    // POST /api/auth/login answers with Set-Cookie: refresh_token (httpOnly);
    // withCredentials is what lets the browser store and re-send it.
    expect(api.defaults.withCredentials).toBe(true);
  });
});

describe('me', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('GETs /auth/me and returns the profile from the envelope', async () => {
    const profile = { id: 5, username: 'toni', email: 'toni@example.com', roles: ['USER'] };
    getSpy.mockResolvedValueOnce(envelope(profile));

    const result = await me();

    expect(getSpy).toHaveBeenCalledWith('/auth/me');
    expect(result).toEqual(profile);
    // the projection carries no secret material — the hash stays server-side
    expect('passwordHash' in result).toBe(false);
  });

  it('propagates the 401 when no session exists so the store can log out', async () => {
    const failure = {
      isAxiosError: true,
      response: {
        status: 401,
        data: { code: 401, message: 'unauthorized', data: null },
      },
    };
    getSpy.mockRejectedValueOnce(failure);

    await expect(me()).rejects.toBe(failure);
  });
});
