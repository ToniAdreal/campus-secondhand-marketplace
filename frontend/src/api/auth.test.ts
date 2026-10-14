import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './client';
import {
  login,
  me,
  changePassword,
  changeEmail,
  closeAccount,
  listSessions,
  revokeSession,
  revokeOtherSessions,
  requestPasswordReset,
  confirmPasswordReset,
  register,
  confirmEmailVerification,
  resendEmailVerification,
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

describe('changeEmail', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('posts to /auth/email-change and resolves with no credential (every session died)', async () => {
    postSpy.mockResolvedValueOnce(envelope(null));

    const result = await changeEmail({
      currentPassword: 'old',
      newEmail: 'new@example.com',
    });

    expect(postSpy).toHaveBeenCalledWith('/auth/email-change', {
      currentPassword: 'old',
      newEmail: 'new@example.com',
    });
    // No fresh pair is returned — the account must verify the new address
    // and sign in again, so the function resolves void.
    expect(result).toBeUndefined();
  });

  it('propagates the envelope error (401 wrong current password, 409 taken address)', async () => {
    const failure = {
      isAxiosError: true,
      response: {
        status: 409,
        data: { code: 409, message: 'email is already registered', data: null },
      },
    };
    postSpy.mockRejectedValueOnce(failure);

    await expect(
      changeEmail({ currentPassword: 'old', newEmail: 'taken@example.com' }),
    ).rejects.toBe(failure);
  });
});

describe('closeAccount', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('DELETEs /auth/me with the proof payload in the body and resolves void', async () => {
    deleteSpy.mockResolvedValueOnce(envelope(null));

    const result = await closeAccount({ currentPassword: 'old', code: '123456' });

    expect(deleteSpy).toHaveBeenCalledWith('/auth/me', {
      data: { currentPassword: 'old', code: '123456' },
    });
    // No credential is returned — the account and every session are gone.
    expect(result).toBeUndefined();
  });

  it('propagates the identical 401 for a wrong password or code', async () => {
    const failure = {
      isAxiosError: true,
      response: {
        status: 401,
        data: { code: 401, message: 'invalid credentials', data: null },
      },
    };
    deleteSpy.mockRejectedValueOnce(failure);

    await expect(closeAccount({ currentPassword: 'wrong' })).rejects.toBe(failure);
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

  it('POSTs /auth/sessions/revoke-others and returns the revoked count', async () => {
    postSpy.mockResolvedValueOnce(envelope({ revokedCount: 2 }));

    const result = await revokeOtherSessions();

    expect(postSpy).toHaveBeenCalledWith('/auth/sessions/revoke-others');
    expect(result).toBe(2);
    // no client-supplied "current" id is ever sent — the server identifies
    // the surviving session from the httpOnly cookie alone
    expect(deleteSpy).not.toHaveBeenCalled();
  });

  it('propagates the 401 from revoke-others when no session exists', async () => {
    const failure = {
      isAxiosError: true,
      response: { status: 401, data: { code: 401, message: 'unauthorized', data: null } },
    };
    postSpy.mockRejectedValueOnce(failure);

    await expect(revokeOtherSessions()).rejects.toBe(failure);
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

describe('email verification (backlog #138)', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('register maps a session-less 201 body to the verification-required outcome', async () => {
    postSpy.mockResolvedValueOnce({
      status: 201,
      data: {
        code: 0,
        message: 'ok',
        data: {
          user: { id: 5, username: 'toni', email: 'toni@example.com', roles: ['USER'] },
          emailVerificationRequired: true,
        },
      },
    } as never);

    const result = await register({
      username: 'toni',
      email: 'toni@example.com',
      password: 's3cret-pass1',
    });

    expect(postSpy).toHaveBeenCalledWith('/auth/register', {
      username: 'toni',
      email: 'toni@example.com',
      password: 's3cret-pass1',
    });
    expect(result).toEqual({
      kind: 'verification-required',
      user: { id: 5, username: 'toni', email: 'toni@example.com', roles: ['USER'] },
    });
  });

  it('register maps a token-pair body (gate off) to the authenticated outcome', async () => {
    postSpy.mockResolvedValueOnce(envelope(authBody));

    const result = await register({
      username: 'toni',
      email: 'toni@example.com',
      password: 's3cret-pass1',
    });

    expect(result).toEqual({
      kind: 'authenticated',
      accessToken: 'tok-abc',
      user: { id: 5, username: 'toni', email: 'toni@example.com', roles: ['USER'] },
    });
  });

  it('posts the token to /auth/email-verification/confirm', async () => {
    postSpy.mockResolvedValueOnce(envelope(null));

    await confirmEmailVerification({ token: 'raw-token-abc' });

    expect(postSpy).toHaveBeenCalledWith('/auth/email-verification/confirm', {
      token: 'raw-token-abc',
    });
  });

  it('posts the address to /auth/email-verification/resend', async () => {
    postSpy.mockResolvedValueOnce(envelope(null));

    await resendEmailVerification({ email: 'toni@example.com' });

    expect(postSpy).toHaveBeenCalledWith('/auth/email-verification/resend', {
      email: 'toni@example.com',
    });
  });

  it('propagates the identical 400 for unknown/used/expired tokens (no oracle to leak)', async () => {
    const failure = {
      isAxiosError: true,
      response: {
        status: 400,
        data: { code: 400, message: 'invalid or expired verification token', data: null },
      },
    };
    postSpy.mockRejectedValueOnce(failure);

    await expect(confirmEmailVerification({ token: 'stale' })).rejects.toBe(failure);
  });
});
