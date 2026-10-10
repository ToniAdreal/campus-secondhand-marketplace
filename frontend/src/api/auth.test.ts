import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './client';
import {
  login,
  me,
  changePassword,
  listSessions,
  revokeSession,
  requestPasswordReset,
  confirmPasswordReset,
  authenticateTotp,
  setupTotp,
  enableTotp,
  recoveryCodeCount,
  disableTotp,
} from './auth';

const postSpy = vi.spyOn(api, 'post');
const getSpy = vi.spyOn(api, 'get');
const deleteSpy = vi.spyOn(api, 'delete');

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
      kind: 'authenticated',
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

describe('sessions', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  const sessionList = [
    {
      id: 'family-current',
      userAgent: 'Mozilla/5.0 (X11; Linux x86_64) Chrome/126.0',
      ipAddress: '203.0.113.7',
      createdAt: '2026-10-08T01:00:00Z',
      lastActiveAt: '2026-10-09T01:30:00Z',
      current: true,
    },
    {
      id: 'family-other',
      userAgent: null,
      ipAddress: '198.51.100.23',
      createdAt: '2026-10-07T12:00:00Z',
      lastActiveAt: '2026-10-08T12:00:00Z',
      current: false,
    },
  ];

  it('GETs /auth/sessions and returns the session list from the envelope', async () => {
    getSpy.mockResolvedValueOnce(envelope(sessionList));

    const result = await listSessions();

    expect(getSpy).toHaveBeenCalledWith('/auth/sessions');
    expect(result).toEqual(sessionList);
    // the current flag and the null device label (pre-capture session)
    // pass through untouched — rendering fallbacks live in the page
    expect(result[0].current).toBe(true);
    expect(result[1].userAgent).toBeNull();
  });

  it('propagates the 401 when no session exists', async () => {
    const failure = {
      isAxiosError: true,
      response: { status: 401, data: { code: 401, message: 'unauthorized', data: null } },
    };
    getSpy.mockRejectedValueOnce(failure);

    await expect(listSessions()).rejects.toBe(failure);
  });

  it('DELETEs /auth/sessions/{id} to revoke one session', async () => {
    deleteSpy.mockResolvedValueOnce(envelope(null));

    await revokeSession('family-other');

    expect(deleteSpy).toHaveBeenCalledWith('/auth/sessions/family-other');
    // revoking one session never touches the all-sessions logout endpoint
    expect(postSpy).not.toHaveBeenCalled();
  });

  it('propagates the 404 for an unknown or foreign session id (no oracle)', async () => {
    const failure = {
      isAxiosError: true,
      response: { status: 404, data: { code: 404, message: 'session not found', data: null } },
    };
    deleteSpy.mockRejectedValueOnce(failure);

    await expect(revokeSession('family-foreign')).rejects.toBe(failure);
  });
});

describe('two-factor (backlog #100)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('login on a 2FA-enabled account returns the 202 challenge, not a token pair', async () => {
    postSpy.mockResolvedValueOnce({
      status: 202,
      data: {
        code: 0,
        message: 'ok',
        data: { challenge: 'chal-123', expiresAt: '2026-10-09T05:00:00Z' },
      },
    } as never);

    const result = await login({ usernameOrEmail: 'toni', password: 'secret' });

    expect(result).toEqual({
      kind: 'totp-challenge',
      challenge: 'chal-123',
      expiresAt: '2026-10-09T05:00:00Z',
    });
    expect('accessToken' in result).toBe(false);
  });

  it('authenticateTotp posts challenge + code (TOTP or recovery) and returns the pair', async () => {
    postSpy.mockResolvedValueOnce(envelope(authBody));

    const result = await authenticateTotp({ challenge: 'chal-123', code: '123456' });

    expect(postSpy).toHaveBeenCalledWith('/auth/2fa/authenticate', {
      challenge: 'chal-123',
      code: '123456',
    });
    expect(result).toEqual({
      accessToken: 'tok-abc',
      user: { id: 5, username: 'toni', email: 'toni@example.com', roles: ['USER'] },
    });
  });

  it('authenticateTotp propagates the identical 401 for a wrong code', async () => {
    const failure = {
      isAxiosError: true,
      response: {
        status: 401,
        data: { code: 401, message: 'invalid credentials', data: null },
      },
    };
    postSpy.mockRejectedValueOnce(failure);

    await expect(authenticateTotp({ challenge: 'chal-123', code: '000000' })).rejects.toBe(
      failure,
    );
  });

  it('setupTotp posts to /auth/2fa/setup and returns secret + otpauth URI', async () => {
    postSpy.mockResolvedValueOnce(
      envelope({
        secret: 'JBSWY3DPEHPK3PXP',
        otpauthUri: 'otpauth://totp/CampusMarketplace:toni?secret=JBSWY3DPEHPK3PXP',
      }),
    );

    const result = await setupTotp();

    expect(postSpy).toHaveBeenCalledWith('/auth/2fa/setup');
    expect(result.secret).toBe('JBSWY3DPEHPK3PXP');
    expect(result.otpauthUri).toContain('otpauth://totp/');
  });

  it('enableTotp posts the code and returns the one-time recovery codes', async () => {
    const codes = ['AAAA-BBBB-CCCC-DDDD', 'EEEE-FFFF-GGGG-HHHH'];
    postSpy.mockResolvedValueOnce(envelope({ recoveryCodes: codes }));

    const result = await enableTotp({ code: '123456' });

    expect(postSpy).toHaveBeenCalledWith('/auth/2fa/enable', { code: '123456' });
    expect(result).toEqual({ recoveryCodes: codes });
  });

  it('recoveryCodeCount GETs the count endpoint and returns the remaining number', async () => {
    getSpy.mockResolvedValueOnce(envelope({ remaining: 7 }));

    const result = await recoveryCodeCount();

    expect(getSpy).toHaveBeenCalledWith('/auth/2fa/recovery-codes/count');
    expect(result).toBe(7);
  });

  it('disableTotp posts the password + code proof to /auth/2fa/disable (backlog #121)', async () => {
    postSpy.mockResolvedValueOnce(envelope(null));

    await disableTotp({ currentPassword: 's3cret-pass', code: '123456' });

    expect(postSpy).toHaveBeenCalledWith('/auth/2fa/disable', {
      currentPassword: 's3cret-pass',
      code: '123456',
    });
  });
});

describe('password reset (backlog #99)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('posts the identifier to /auth/password-reset', async () => {
    postSpy.mockResolvedValueOnce(envelope(null));

    await requestPasswordReset({ usernameOrEmail: 'toni@example.com' });

    expect(postSpy).toHaveBeenCalledWith('/auth/password-reset', {
      usernameOrEmail: 'toni@example.com',
    });
  });

  it('posts token + new password to /auth/password-reset/confirm', async () => {
    postSpy.mockResolvedValueOnce(envelope(null));

    await confirmPasswordReset({ token: 'raw-token-abc', newPassword: 'new-strong-1' });

    expect(postSpy).toHaveBeenCalledWith('/auth/password-reset/confirm', {
      token: 'raw-token-abc',
      newPassword: 'new-strong-1',
    });
  });

  it('propagates the identical 401 for unknown/used/expired tokens (no oracle to leak)', async () => {
    const failure = {
      isAxiosError: true,
      response: {
        status: 401,
        data: { code: 401, message: 'invalid or expired reset token', data: null },
      },
    };
    postSpy.mockRejectedValueOnce(failure);

    await expect(
      confirmPasswordReset({ token: 'stale', newPassword: 'new-strong-1' }),
    ).rejects.toBe(failure);
  });

  it('propagates the 400 strength reason verbatim on confirm', async () => {
    const failure = {
      isAxiosError: true,
      response: {
        status: 400,
        data: { code: 400, message: 'password must be 8-72 characters', data: null },
      },
    };
    postSpy.mockRejectedValueOnce(failure);

    await expect(confirmPasswordReset({ token: 'tok', newPassword: 'x' })).rejects.toBe(failure);
  });
});
