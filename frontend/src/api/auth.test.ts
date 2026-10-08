import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './client';
import { login, me, changePassword } from './auth';

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

describe('changePassword', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('posts to /auth/password and returns the fresh bearer credential for this session', async () => {
    const freshPair = {
      accessToken: 'tok-fresh',
      tokenType: 'Bearer',
      expiresInSeconds: 900,
      user: { id: 5, username: 'toni', email: 'toni@example.com', roles: ['USER'] },
    };
    postSpy.mockResolvedValueOnce(envelope(freshPair));

    const result = await changePassword({ currentPassword: 'old', newPassword: 'new-strong-1' });

    expect(postSpy).toHaveBeenCalledWith('/auth/password', {
      currentPassword: 'old',
      newPassword: 'new-strong-1',
    });
    // the backend rotated the refresh-token family; this response carries
    // the new pair for the current session — the caller must store it
    expect(result).toEqual({
      accessToken: 'tok-fresh',
      user: { id: 5, username: 'toni', email: 'toni@example.com', roles: ['USER'] },
    });
    expect('refreshToken' in (result as object)).toBe(false);
  });

  it('propagates the envelope error (401 wrong current password, 400 weak new password)', async () => {
    const failure = {
      isAxiosError: true,
      response: {
        status: 400,
        data: { code: 400, message: 'password must be 8-72 characters', data: null },
      },
    };
    postSpy.mockRejectedValueOnce(failure);

    await expect(changePassword({ currentPassword: 'old', newPassword: 'x' })).rejects.toBe(failure);
  });
});
